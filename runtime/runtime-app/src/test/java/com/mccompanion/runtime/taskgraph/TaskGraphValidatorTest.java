package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TaskGraphValidatorTest {
    private final TaskGraphValidator validator = new TaskGraphValidator();

    @Test
    void acceptsAllDeclarativeNodeTypesWithBoundedControlFlow() {
        JsonNode graph = TaskGraphCodec.parse("""
                version: mcac-task-graph/1
                id: generic-composition
                inputs:
                  target:
                    type: registry_block
                permissions: [READ_WORLD, MOVE, MEMORY]
                limits:
                  maxNodes: 64
                  maxDepth: 12
                  maxLoopIterations: 8
                  maxRetriesPerNode: 3
                  maxParallelNodes: 2
                  maxToolCalls: 16
                root:
                  id: root
                  type: sequence
                  nodes:
                    - {id: observe, type: call_tool, tool: world.observe, arguments: {}}
                    - id: choose
                      type: if
                      condition: "${state.ready == true && inputs.target != null}"
                      then:
                        id: routes
                        type: parallel
                        maxConcurrency: 2
                        nodes:
                          - {id: wait, type: wait, durationMillis: 10}
                          - {id: progress, type: emit_progress, message: checking}
                      else:
                        id: alternatives
                        type: fallback
                        nodes:
                          - id: retry-observe
                            type: retry
                            maxAttempts: 2
                            backoffMillis: 10
                            node: {id: observe-again, type: call_tool, tool: world.observe}
                          - {id: ask, type: ask_user, prompt: "Continue?", options: [continue, stop]}
                    - id: repeated
                      type: repeat
                      maxIterations: 2
                      until: "${state.done == true}"
                      body: {id: checkpoint, type: checkpoint, label: repeated}
                    - id: loop
                      type: while
                      condition: "${state.pending == true}"
                      maxIterations: 2
                      body: {id: memory, type: read_memory, kind: WORLD, query: target}
                    - id: select
                      type: switch
                      expression: "${state.result}"
                      cases:
                        - equals: ok
                          node: {id: suggest, type: suggest_memory, kind: EPISODIC, content: completed}
                      default: {id: failure, type: fail, code: NO_RESULT, message: no result}
                    - {id: done, type: return, value: ok}
                """, TaskGraphCodec.Format.YAML);

        TaskGraphValidationResult result = validator.validate(graph, Set.of("world.observe", "memory.search"));

        assertTrue(result.valid(), result.issues().toString());
        assertEquals("generic-composition", result.graphId());
        assertEquals(18, result.nodeCount());
        assertTrue(result.depth() <= 12);
    }

    @Test
    void rejectsUnboundedControlFlowUnsafeExpressionsAndUnavailableTools() {
        JsonNode graph = Json.parse("""
                {
                  "version":"mcac-task-graph/1",
                  "id":"unsafe",
                  "permissions":["READ_WORLD"],
                  "root":{
                    "id":"root","type":"sequence","nodes":[
                      {"id":"loop","type":"while","condition":"${java.lang.Runtime.exec('x')}",
                       "maxIterations":65,"body":{"id":"same","type":"wait","durationMillis":1}},
                      {"id":"same","type":"retry","maxAttempts":6,"backoffMillis":0,
                       "node":{"id":"shell","type":"call_tool","tool":"shell.execute"}}
                    ]
                  }
                }
                """);

        TaskGraphValidationResult result = validator.validate(graph, Set.of("world.observe"));

        assertFalse(result.valid());
        Set<String> codes = result.issues().stream().map(TaskGraphValidationIssue::code)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.contains("UNSAFE_EXPRESSION"));
        assertTrue(codes.contains("VALUE_OUT_OF_RANGE"));
        assertTrue(codes.contains("DUPLICATE_NODE_ID"));
        assertTrue(codes.contains("TOOL_UNAVAILABLE"));
    }

    @Test
    void rejectsLimitEscalationDuplicateYamlKeysAndOversizedDocuments() {
        JsonNode graph = TaskGraphCodec.parse("""
                version: mcac-task-graph/1
                id: limits
                permissions: []
                limits:
                  maxNodes: 257
                root: {id: done, type: return}
                """, TaskGraphCodec.Format.YAML);
        assertFalse(validator.validate(graph, Set.of()).valid());
        assertThrows(IllegalArgumentException.class, () -> TaskGraphCodec.parse("""
                version: mcac-task-graph/1
                id: one
                id: two
                """, TaskGraphCodec.Format.YAML));
        assertThrows(IllegalArgumentException.class, () ->
                TaskGraphCodec.parse("x".repeat(2 * 1024 * 1024 + 1), TaskGraphCodec.Format.JSON));
    }

    @Test
    void validatesLiteralBoundedArrayReferencesBeforeExecution() {
        JsonNode valid = Json.parse("""
                {"version":"mcac-task-graph/1","id":"array-valid","permissions":["MOVE"],
                 "root":{"id":"move","type":"call_tool","tool":"movement.navigate",
                  "arguments":{"x":"${outputs.scan.candidates[0].position.x}","y":64,"z":0}}}
                """);
        assertTrue(validator.validate(valid, Set.of("movement.navigate")).valid());

        JsonNode invalid = Json.parse("""
                {"version":"mcac-task-graph/1","id":"array-invalid","permissions":["MOVE"],
                 "root":{"id":"root","type":"sequence","nodes":[
                   {"id":"dynamic","type":"call_tool","tool":"movement.navigate",
                    "arguments":{"x":"${outputs.scan.candidates[inputs.index].position.x}"}},
                   {"id":"large","type":"return","value":"${outputs.scan.candidates[256]}"}
                 ]}}
                """);
        TaskGraphValidationResult result = validator.validate(invalid, Set.of("movement.navigate"));

        assertFalse(result.valid());
        assertEquals(2, result.issues().stream()
                .filter(issue -> issue.code().equals("INVALID_REFERENCE")).count());
    }
}
