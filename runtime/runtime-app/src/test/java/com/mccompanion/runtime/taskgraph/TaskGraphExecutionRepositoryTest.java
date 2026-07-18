package com.mccompanion.runtime.taskgraph;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TaskGraphExecutionRepositoryTest {
    @TempDir Path temporary;

    @Test
    void persistsBoundedStateAndRequiresReconciliationAfterRestart() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("graph.db"))) {
            database.initialize();
            TaskGraphExecutionRepository repository = new TaskGraphExecutionRepository(database);
            var graph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"durable","permissions":["READ_WORLD"],
                     "root":{"id":"done","type":"return"}}
                    """);
            var created = repository.create("execution-1",
                    new ToolContext("hermes", "brain-1", "companion-1"), graph,
                    TaskGraphLimits.DEFAULTS, Json.object().put("target", "minecraft:stone"),
                    Json.object().put("provider", "replay"));
            assertEquals("READY", created.state());
            assertEquals(64, created.graphHash().length());
            var running = repository.save(created.executionId(), created.revision(), "RUNNING", "observe",
                    Json.parse("[\"start\"]"), Json.object(), Json.object(),
                    Json.object().put("observe", "value"),
                    Json.parse("[{\"nodeId\":\"start\"}]"), Json.parse("[{\"type\":\"checkpoint\"}]"),
                    null, Json.object().put("partial", true), "RUNNING");
            assertEquals(1, running.revision());
            assertEquals("observe", running.currentNodeId());

            assertEquals(1, repository.markUnfinishedForReconciliation());
            var recovered = repository.get("execution-1").orElseThrow();
            assertEquals("RECONCILIATION_REQUIRED", recovered.state());
            assertEquals("RUNTIME_RESTARTED", recovered.resultCode());
            assertTrue(recovered.result().path("partial").asBoolean());
            assertEquals("value", recovered.outputs().path("observe").asText());
            assertEquals(2, recovered.revision());
            var summaries = repository.listByCompanion("companion-1", 10);
            assertEquals(1, summaries.size());
            assertEquals("durable", summaries.getFirst().graphId());
            assertEquals("observe", summaries.getFirst().currentNodeId());
            assertEquals(1, summaries.getFirst().completedNodeCount());
            assertTrue(repository.listByCompanion("other-companion", 10).isEmpty());
            assertThrows(IllegalStateException.class, () -> repository.save("execution-1", 0, "RUNNING",
                    "other", Json.parse("[]"), Json.object(), Json.object(), Json.object(), Json.parse("[]"),
                    Json.parse("[]"), null, Json.MAPPER.nullNode(), "RUNNING"));
        }
    }

    @Test
    void restartPreservesSafelyPausedAndWaitingExecutions() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("safe-states.db"))) {
            database.initialize();
            TaskGraphExecutionRepository repository = new TaskGraphExecutionRepository(database);
            var graph = Json.parse("""
                    {"version":"mcac-task-graph/1","id":"safe","permissions":[],
                     "root":{"id":"done","type":"return"}}
                    """);
            var waiting = repository.create("waiting",
                    new ToolContext("hermes", "brain-1", "companion-1"), graph,
                    TaskGraphLimits.DEFAULTS, Json.object(), Json.object());
            waiting = repository.save(waiting.executionId(), waiting.revision(), "WAITING", "ask",
                    Json.parse("[]"), Json.object(), Json.object(), Json.object(), Json.parse("[]"),
                    Json.parse("[]"), Json.object().put("questionId", "question-1"),
                    Json.MAPPER.nullNode(), "TASK_GRAPH_WAITING_USER");
            var paused = repository.create("paused",
                    new ToolContext("hermes", "brain-2", "companion-1"), graph,
                    TaskGraphLimits.DEFAULTS, Json.object(), Json.object());
            repository.save(paused.executionId(), paused.revision(), "PAUSED", "wait",
                    Json.parse("[]"), Json.object(), Json.object(), Json.object(), Json.parse("[]"),
                    Json.parse("[]"), null, Json.MAPPER.nullNode(), "TASK_GRAPH_PAUSED");

            assertEquals(0, repository.markUnfinishedForReconciliation());
            assertEquals("WAITING", repository.get("waiting").orElseThrow().state());
            assertEquals("PAUSED", repository.get("paused").orElseThrow().state());
        }
    }
}
