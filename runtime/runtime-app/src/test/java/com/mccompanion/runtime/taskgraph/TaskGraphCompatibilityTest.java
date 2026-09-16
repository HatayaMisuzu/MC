package com.mccompanion.runtime.taskgraph;

import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolDefinition;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TaskGraphCompatibilityTest {
    private static final com.fasterxml.jackson.databind.JsonNode GRAPH = Json.parse("""
            {"root":{"id":"steps","type":"sequence","nodes":[
              {"id":"observed","type":"call_tool","tool":"test.observe"},
              {"id":"move","type":"call_tool","tool":"test.move"}
            ]}}
            """);

    @Test void checksOnlyToolsUsedByPendingNodes() {
        var initial = Map.of("test.observe", definition("test.observe", "1"),
                "test.move", definition("test.move", "1"));
        var persisted = TaskGraphCompatibility.capture(GRAPH, initial, binding("session-1", "world-1"));
        var completed = Json.MAPPER.createArrayNode().add("observed");

        var unrelatedCompletedChange = TaskGraphCompatibility.assessResume(persisted, GRAPH, completed,
                Map.of("test.observe", definition("test.observe", "2"),
                        "test.move", definition("test.move", "1")),
                binding("session-1", "world-1"));
        assertTrue(unrelatedCompletedChange.compatible());

        var pendingChange = TaskGraphCompatibility.assessResume(persisted, GRAPH, completed,
                Map.of("test.observe", definition("test.observe", "2"),
                        "test.move", definition("test.move", "2")),
                binding("session-1", "world-1"));
        assertFalse(pendingChange.compatible());
        assertEquals("TASK_GRAPH_TOOL_CONTRACT_CHANGED", pendingChange.code());
    }

    @Test void sessionCanRefreshAtSafeResumeButNotDuringExecution() {
        var definitions = Map.of("test.observe", definition("test.observe", "1"),
                "test.move", definition("test.move", "1"));
        var persisted = TaskGraphCompatibility.capture(GRAPH, definitions, binding("session-1", "world-1"));
        var current = binding("session-2", "world-1");

        var resume = TaskGraphCompatibility.assessResume(persisted, GRAPH,
                Json.MAPPER.createArrayNode(), definitions, current);
        assertTrue(resume.compatible());
        assertTrue(resume.refreshRequired());

        var activeCall = TaskGraphCompatibility.assessCall(
                persisted, "test.move", definitions.get("test.move"), current);
        assertFalse(activeCall.compatible());
        assertEquals("TASK_GRAPH_SESSION_CHANGED", activeCall.code());
    }

    @Test void worldOrTargetChangeCannotBeRefreshed() {
        var definitions = Map.of("test.observe", definition("test.observe", "1"),
                "test.move", definition("test.move", "1"));
        var persisted = TaskGraphCompatibility.capture(GRAPH, definitions, binding("session-1", "world-1"));

        var changed = TaskGraphCompatibility.assessResume(persisted, GRAPH,
                Json.MAPPER.createArrayNode(), definitions, binding("session-2", "world-2"));
        assertFalse(changed.compatible());
        assertEquals("TASK_GRAPH_WORLD_CHANGED", changed.code());
    }

    @Test void persistedContractsKeepCompletedNodesValidForRecoveryParsing() {
        var initial = Map.of("test.observe", definition("test.observe", "1"),
                "test.move", definition("test.move", "1"));
        var persisted = TaskGraphCompatibility.capture(GRAPH, initial, binding("session-1", "world-1"));
        var merged = TaskGraphCompatibility.definitionsForValidation(
                Map.of("test.move", definition("test.move", "1")), persisted);

        assertTrue(merged.containsKey("test.observe"));
        assertEquals("1", merged.get("test.observe").version());
    }

    private static ToolDefinition definition(String name, String version) {
        return new ToolDefinition(name, version, "test", Json.object().put("type", "object"),
                "LOW", "READ_WORLD", Duration.ofSeconds(2), true);
    }

    private static com.fasterxml.jackson.databind.JsonNode binding(String session, String world) {
        return Json.object().put("connected", true).put("targetId", "fabric-1.21.1")
                .put("worldId", world).put("sessionId", session)
                .put("protocol", "mc-companion/2").put("capabilityRevision", 0);
    }
}
