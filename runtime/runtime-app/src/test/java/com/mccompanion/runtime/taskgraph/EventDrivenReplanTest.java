package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.brain.*;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.event.RuntimeEvent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** UNIT / SQLite INTEGRATION / REPLAY. Not Minecraft, Live-provider, or human evidence. */
class EventDrivenReplanTest {
    @TempDir Path temporary;
    static final ToolContext OWNER = new ToolContext("runtime-primary", "original-brain", "companion");
    static final String GOAL = "Collect resources and deliver them to the original destination";

    @Test void externalBrainRewritesOnlyRemainderThenResumesAndDoesNotReplayCompletedEffect() throws Exception {
        try (Fixture f = new Fixture(temporary.resolve("loop.db"))) {
            f.start();
            AtomicInteger requests = new AtomicInteger();
            try (ExternalBrainCoordinator brain = f.brain(request -> {
                if (request.userMessage().isBlank()) {
                    assertEquals("REPLAN_TOOL_NOT_ALLOWED", request.toolResults().getFirst().code());
                    assertEquals("REPLAN_APPLIED", request.toolResults().getLast().code());
                    return BrainTurnResult.finalResponse("Remaining plan accepted, not yet complete.");
                }
                requests.incrementAndGet();
                JsonNode envelope = Json.parse(request.userMessage());
                assertEquals(GOAL, envelope.path("originalGoal").asText());
                assertEquals("done", envelope.path("completedNodes").get(0).asText());
                assertEquals("WORLD_MODEL", envelope.path("worldContext").path("source").asText());
                return BrainTurnResult.tools(List.of(new ToolCall("forbidden", "test.write", Json.object()),
                        rewriteCall(envelope, finishGraph())));
            })) {
                var event = event("ROUTE_INVALIDATED", "route", Json.object());
                var result = brain.continueEvent(OWNER.controllerId(), event, context());
                assertEquals("REPLAN_RESUMED", result.code());
                f.await("SUCCEEDED");
                assertEquals(List.of("done", "remaining"), f.gateway.effects);
                assertEquals("RESUMED", f.record().replan().path("phase").asText());
                assertTrue(brain.graphOwnsEvent(OWNER.controllerId(), event)); // Terminal ownership still suppresses old failures.
                brain.continueEvent(OWNER.controllerId(), event, context());
                assertEquals(1, requests.get());
                assertEquals(GOAL, f.record().replan().path("originalGoal").asText());
            }
        }
    }

    @Test void rewriteRejectsChangedCompletedNodesPermissionsStaleRevisionAndWrongOwner() throws Exception {
        try (Fixture f = new Fixture(temporary.resolve("validation.db"))) {
            f.start(); var record = f.prepare("INVENTORY_FULL", "full");
            ObjectNode removed = finishGraph(); ((com.fasterxml.jackson.databind.node.ArrayNode) removed.path("root").path("nodes")).remove(0);
            assertEquals("REPLAN_COMPLETED_SCOPE_CHANGED", f.apply(record, removed).code());
            ObjectNode changed = finishGraph(); ((ObjectNode) changed.path("root").path("nodes").get(0)).put("tool", "world.observe");
            assertEquals("REPLAN_COMPLETED_NODE_CHANGED", f.apply(record, changed).code());
            ObjectNode control = finishGraph(); ((ObjectNode) control.path("root")).put("type", "fallback");
            assertEquals("REPLAN_CONTROL_CHANGED", f.apply(record, control).code());
            ObjectNode authority = finishGraph(); authority.withArray("permissions").add("READ_WORLD");
            assertEquals("REPLAN_AUTHORITY_CHANGED", f.apply(record, authority).code());
            ToolCall valid = rewriteCall(record.replan().path("request"), finishGraph());
            assertFalse(f.runtime.replan(new ToolContext("other", "brain", "companion"), valid, "execution",
                    record.replan().path("requestId").asText(), 0, record.revision(), finishGraph()).success());
            assertEquals("STALE_REPLAN_REVISION", f.runtime.replan(OWNER, valid, "execution",
                    record.replan().path("requestId").asText(), 1, record.revision(), finishGraph()).code());
            assertEquals("REPLAN_REQUIRED", f.runtime.resume(OWNER, valid, "execution").code());
            assertEquals("REPLAN_APPLIED", f.apply(record, finishGraph()).code());
            assertEquals("REPLAN_ALREADY_APPLIED", f.apply(record, finishGraph()).code());
            ObjectNode conflict = finishGraph(); ((ObjectNode) conflict.path("root").path("nodes").get(2)).put("value", "other");
            assertEquals("REPLAN_IDEMPOTENCY_CONFLICT", f.apply(record, conflict).code());
        }
    }

    @Test void crashWindowsPendingBrainAppliedAndPendingResumeKeepOneEffectAndStableEpoch() throws Exception {
        Path path = temporary.resolve("restart.db");
        TaskGraphExecutionRecord requested;
        try (Fixture f = new Fixture(path)) { f.start(); requested = f.prepare("TOOL_BROKEN", "broken"); }
        try (Fixture f = new Fixture(path)) {
            assertEquals("WAITING_BRAIN", f.record().replan().path("phase").asText());
            requested = f.prepare("TOOL_BROKEN", "broken");
            assertEquals(2, requested.replan().path("deliveries").asInt());
            assertEquals("REPLAN_APPLIED", f.apply(requested, finishGraph()).code());
        }
        try (Fixture f = new Fixture(path)) {
            assertEquals("APPLIED", f.record().replan().path("phase").asText());
            assertEquals(1, f.record().replan().path("epoch").asInt());
            assertEquals("REPLAN_ALREADY_APPLIED", f.apply(requested, finishGraph()).code());
            assertTrue(f.runtime.resumeReplan(f.record()).success());
            f.await("SUCCEEDED"); assertEquals(List.of("remaining"), f.gateway.effects);
        }
    }

    @Test void providerFailureBudgetPersistsAndCannotBeResetByDifferentEvent() throws Exception {
        try (Fixture f = new Fixture(temporary.resolve("provider.db"))) {
            f.start(); f.prepare("ROUTE_INVALIDATED", "route"); f.prepare("ROUTE_INVALIDATED", "route");
            assertTrue(f.runtime.prepareReplan(OWNER.controllerId(), event("ROUTE_INVALIDATED", "route", Json.object()), Json.object()).isEmpty());
            assertEquals("REPLAN_BUDGET_EXHAUSTED", f.record().replan().path("code").asText());
            assertTrue(f.runtime.prepareReplan(OWNER.controllerId(), event("INVENTORY_FULL", "different", Json.object()), Json.object()).isEmpty());
            assertEquals(2, f.record().replan().path("deliveries").asInt());
            assertEquals("PAUSED", f.record().state());
        }
    }

    @Test void totalReplanBudgetStopsFeedbackAcrossRevisions() throws Exception {
        try (Fixture f = new Fixture(temporary.resolve("budget.db"))) {
            f.start();
            for (int i = 0; i < TaskGraphReplan.MAX_REPLANS; i++) {
                var record = f.prepare("INVENTORY_FULL", "full-" + i);
                ObjectNode graph = waitingGraph("wait-" + i);
                assertTrue(f.apply(record, graph).success()); assertTrue(f.runtime.resumeReplan(record).success());
                f.await("WAITING");
            }
            assertTrue(f.runtime.prepareReplan(OWNER.controllerId(), event("INVENTORY_FULL", "over", Json.object()), Json.object()).isEmpty());
            assertEquals("BLOCKED", f.record().replan().path("phase").asText());
            assertEquals(4, f.record().replan().path("epoch").asInt());
            assertEquals(List.of("done"), f.gateway.effects);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"DEATH", "ROUTE_INVALIDATED", "INVENTORY_FULL", "TOOL_BROKEN", "CURRENT_TARGET_DIED", "USER_INSTRUCTION"})
    void sixSemanticTriggersAndLocalRecoveryFirst(String type) throws Exception {
        try (Fixture f = new Fixture(temporary.resolve(type + ".db"))) {
            f.start();
            var local = event(type, "local", Json.object().put("localRecovery", "RUNNING"));
            if (type.equals("DEATH")) assertTrue(TaskGraphReplan.semantic(local)); // Death cannot be masked as local recovery.
            else {
                assertFalse(TaskGraphReplan.semantic(local));
                assertTrue(f.runtime.prepareReplan(OWNER.controllerId(), local, Json.object()).isEmpty());
            }
            assertEquals("WAITING", f.record().state());
            assertEquals("WAITING_BRAIN", f.prepare(type, "semantic").replan().path("phase").asText());
        }
    }

    @Test void insertedUserInstructionIsExplicitAmendmentNotReplacement() throws Exception {
        try (Fixture f = new Fixture(temporary.resolve("instruction.db"))) {
            f.start();
            try (var brain = f.brain(request -> {
                if (request.userMessage().isBlank()) return BrainTurnResult.finalResponse("Updated remaining priorities.");
                var envelope = Json.parse(request.userMessage());
                assertEquals(GOAL, envelope.path("originalGoal").asText());
                assertEquals("First make room, then continue", envelope.path("event").path("payload").path("instruction").asText());
                return BrainTurnResult.tools(List.of(rewriteCall(envelope, finishGraph())));
            })) {
                assertEquals("REPLAN_RESUMED", brain.continueInstruction(OWNER.controllerId(), OWNER.companionId(),
                        "First make room, then continue", context()).code());
                f.await("SUCCEEDED"); assertEquals(List.of("done", "remaining"), f.gateway.effects);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"UNCERTAIN_EFFECT", "TOOL_CONTROL_RELEASE_UNCONFIRMED", "NESTED_FAILURE"})
    void uncertainToolEffectCannotBeDiscardedByRewriteOrChildCancellation(String code) throws Exception {
        try (Fixture f = new Fixture(temporary.resolve("uncertain.db"))) {
            f.start(); var record = f.prepare("ROUTE_INVALIDATED", "route");
            ObjectNode results = record.toolResults().deepCopy();
            ObjectNode uncertain = Json.object().put("nodeId", "wait").put("toolName", "test.write")
                    .put("success", false).put("code", code.equals("NESTED_FAILURE") ? "TOOL_FAILED" : code);
            ObjectNode observation = uncertain.putObject("observation").put("state", "BLOCKED").put("taskId", "child-1");
            if (code.equals("NESTED_FAILURE"))
                observation.putObject("fabricObservation").putObject("snapshot").put("failureCode", "UNCERTAIN_EFFECT");
            results.set("execution:wait:1", uncertain);
            f.repository.save("execution", record.revision(), "PAUSED", record.currentNodeId(), record.completedNodes(), results,
                    record.variables(), record.outputs(), record.checkpoints(), record.evidence(), record.waitingQuestion(), record.result(), "UNCERTAIN_EFFECT");
            var unsafe = f.record();
            assertEquals("REPLAN_UNCONFIRMED_EFFECT", assertThrows(IllegalArgumentException.class,
                    () -> TaskGraphReplan.validateRewrite(unsafe, finishGraph())).getMessage());
            f.gateway.childState = "BLOCKED";
            assertTrue(f.runtime.prepareReplan(OWNER.controllerId(), event("ROUTE_INVALIDATED", "route", Json.object()), Json.object()).isEmpty());
            assertEquals("BLOCKED", f.record().replan().path("phase").asText());
            assertEquals("REPLAN_UNCONFIRMED_EFFECT", f.record().replan().path("code").asText());
            assertEquals(0, f.gateway.cancellations);
        }
    }

    @Test void instructionWithoutExistingGoalStartsAnOrdinaryUserGoal() throws Exception {
        try (Fixture f = new Fixture(temporary.resolve("new-instruction.db")); var brain = f.brain(request -> {
            if (request.userMessage().isBlank()) return new BrainTurnResult(BrainTurnResult.Kind.WAIT, "", List.of(), "await graph");
            assertEquals(GOAL, request.userMessage());
            return BrainTurnResult.tools(List.of(new ToolCall("execution", "task_graph.execute",
                    Json.object().set("graph", waitingGraph("wait")))));
        })) {
            brain.continueInstruction(OWNER.controllerId(), OWNER.companionId(), GOAL, context());
            f.await("WAITING");
            assertEquals(GOAL, f.record().replan().path("originalGoal").asText());
            assertEquals("IDLE", f.record().replan().path("phase").asText());
        }
    }

    @Test void initialResourceDeficitAndLifecycleFeedbackDoNotReplan() {
        for (String type : List.of("KEY_ITEM_INSUFFICIENT", "KEY_ITEM_ACQUIRED", "RESOURCE_TARGET_REACHED",
                "TASK_GRAPH_STARTED", "TASK_GRAPH_PAUSED", "TASK_GRAPH_RESUMED", "TASK_GRAPH_PROGRESS",
                "TARGET_CONTAINER_CHANGED", "TARGET_BLOCK_CHANGED", "CURRENT_TARGET_DIED"))
            assertFalse(TaskGraphReplan.semantic(event(type, type, Json.object())), type);
    }

    @Test void pendingRequestReturnsToDurableQueueAfterRestartWithoutResettingBudget() throws Exception {
        Path path = temporary.resolve("queue-recovery.db");
        try (Fixture f = new Fixture(path)) { f.start(); f.prepare("INVENTORY_FULL", "persisted-event"); }
        try (Fixture f = new Fixture(path)) {
            var queue = new com.mccompanion.runtime.event.RuntimeEventRepository(f.database);
            var recovered = f.repository.pendingReplanEvents();
            assertEquals(1, recovered.size());
            queue.recoverReplan(recovered.getFirst());
            queue.recoverReplan(recovered.getFirst());
            assertEquals(1, queue.pendingCount("companion"));
            var event = queue.claimReady().orElseThrow();
            assertEquals("persisted-event", event.eventId());
            assertEquals(1, f.record().replan().path("deliveries").asInt());
            assertEquals(1, f.runtime.prepareReplan(OWNER.controllerId(), event, Json.object()).size());
            assertEquals(2, f.record().replan().path("deliveries").asInt());
        }
    }

    @Test void blockedChildIsSettledBeforeReplanAndCancellationIsIdempotent() throws Exception {
        try (Fixture f = new Fixture(temporary.resolve("blocked-child.db"))) {
            f.start();
            var record = f.prepare("ROUTE_INVALIDATED", "route");
            ObjectNode results = record.toolResults().deepCopy();
            results.set("execution:wait:1", Json.object().put("nodeId", "wait").put("toolName", "test.write")
                    .put("success", false).put("code", "TOOL_BLOCKED")
                    .set("observation", Json.object().put("taskId", "child-1").put("state", "BLOCKED")));
            f.repository.save("execution", record.revision(), "PAUSED", record.currentNodeId(), record.completedNodes(),
                    results, record.variables(), record.outputs(), record.checkpoints(), record.evidence(),
                    record.waitingQuestion(), record.result(), "TOOL_BLOCKED");
            f.gateway.childState = "BLOCKED";
            record = f.prepare("ROUTE_INVALIDATED", "route");
            assertEquals(1, f.gateway.cancellations);
            assertEquals("CANCELLED", record.toolResults().path("execution:wait:1").path("observation").path("state").asText());
            assertTrue(f.apply(record, finishGraph()).success());
            assertTrue(f.runtime.resumeReplan(record).success()); f.await("SUCCEEDED");
            assertEquals(1, f.gateway.cancellations);
            assertEquals(List.of("done", "remaining"), f.gateway.effects);
        }
    }

    static RuntimeEvent event(String type, String id, JsonNode payload) {
        Instant now = Instant.now().plusSeconds(1); // Distinct observed edge after the last epoch, without timing sleeps.
        return new RuntimeEvent(id, RuntimeEvent.Category.TASK, type, RuntimeEvent.Priority.CRITICAL,
                "TEST_FIXTURE", "companion", null, "execution", Json.object(), id, null, null,
                now, now, now.plusSeconds(60), payload);
    }
    static AgentContext context() {
        return new AgentContext("companion", Json.object().put("source", "WORLD_MODEL"), List.of(),
                Json.object(), List.of(), List.of(), 5);
    }
    static ObjectNode waitingGraph(String waitId) {
        ObjectNode graph = finishGraph();
        var nodes = (com.fasterxml.jackson.databind.node.ArrayNode) graph.path("root").path("nodes");
        nodes.remove(1); nodes.insert(1, Json.object().put("id", waitId).put("type", "wait").put("durationMillis", 30000));
        return graph;
    }
    static ObjectNode finishGraph() {
        return (ObjectNode) Json.parse("""
            {"version":"mcac-task-graph/1","id":"resource-goal","permissions":["INVENTORY"],
             "root":{"id":"root","type":"sequence","nodes":[
                {"id":"done","type":"call_tool","tool":"test.write","arguments":{}},
                {"id":"remaining","type":"call_tool","tool":"test.write","arguments":{}},
                {"id":"finish","type":"return","value":"original-goal-complete"}]}}
            """);
    }
    static ToolCall rewriteCall(JsonNode request, JsonNode graph) {
        ObjectNode args = Json.object().put("executionId", "execution")
                .put("requestId", request.path("requestId").asText()).put("epoch", request.path("epoch").asLong())
                .put("expectedRevision", request.path("revision").asLong()); args.set("graph", graph);
        return new ToolCall("rewrite-" + request.path("requestId").asText(), "task_graph.replan", args);
    }
    static final class Fixture implements AutoCloseable {
        final RuntimeDatabase database;
        final TaskGraphExecutionRepository repository;
        final Gateway gateway = new Gateway();
        final TaskGraphRuntime runtime;
        Fixture(Path path) throws Exception {
            database = new RuntimeDatabase(path); database.initialize();
            repository = new TaskGraphExecutionRepository(database);
            runtime = new TaskGraphRuntime(gateway, repository); gateway.runtime = runtime;
        }
        void start() throws Exception {
            assertTrue(runtime.start(OWNER, new ToolCall("execution", "task_graph.execute", Json.object()),
                    waitingGraph("wait"), Json.object(), Json.object().put("originalUserGoal", GOAL)).success());
            await("WAITING");
        }
        TaskGraphExecutionRecord record() throws Exception { return repository.get("execution").orElseThrow(); }
        TaskGraphExecutionRecord prepare(String type, String id) throws Exception {
            var records = runtime.prepareReplan(OWNER.controllerId(), event(type, id, Json.object()
                    .put("localRecoveryExhausted", true)), context().verifiedWorld());
            assertEquals(1, records.size(), record().toString()); return records.getFirst();
        }
        ToolResult apply(TaskGraphExecutionRecord request, JsonNode graph) {
            return gateway.execute(OWNER, rewriteCall(request.replan().path("request"), graph));
        }
        ExternalBrainCoordinator brain(java.util.function.Function<BrainTurnRequest, BrainTurnResult> script) {
            var brain = new ExternalBrainCoordinator(new ReplayBrainAdapter(script), gateway, 8);
            brain.attachTaskGraphs(runtime); return brain;
        }
        void await(String expected) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (true) {
                var record = record();
                if (record.state().equals(expected)) return;
                assertFalse(Set.of("FAILED", "CANCELLED", "BLOCKED", "PAUSED", "RECONCILIATION_REQUIRED").contains(record.state()), record.toString());
                assertTrue(System.nanoTime() < deadline, record.toString()); Thread.sleep(5);
            }
        }
        @Override public void close() throws Exception { runtime.close(); database.close(); }
    }
    static final class Gateway implements ToolGateway {
        TaskGraphRuntime runtime;
        String childState;
        int cancellations;
        final List<String> effects = new CopyOnWriteArrayList<>();
        @Override public Optional<ToolResult> inspectDurable(ToolContext context, DurableExecutionReceipt.Handle handle) {
            return Optional.of(new ToolResult("inspect", "task.inspect", false, "TOOL_" + childState,
                    Json.object().put("taskId", handle.id()).put("state", childState), true));
        }
        @Override public void cancelDurable(ToolContext context, ToolCall call, DurableExecutionReceipt.Handle handle, String reason) {
            if (handle.kind().equals("TASK_GRAPH")) { runtime.cancel(context, call, handle.id(), reason); return; }
            assertEquals(OWNER, context); assertEquals("child-1", handle.id());
            assertEquals("execution:wait:1", call.callId()); cancellations++; childState = "CANCELLED";
        }
        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("test.write", "1", "fixture effect", Json.object(), "MEDIUM", "INVENTORY", Duration.ofSeconds(1), false),
                    new ToolDefinition("world.observe", "1", "fixture read", Json.object(), "LOW", "INVENTORY", Duration.ofSeconds(1), true));
        }
        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            var args = call.arguments();
            if (call.name().equals("task_graph.execute")) return runtime.start(context, call,
                    args.path("graph"), Json.object(), args.path("provenance"));
            if (call.name().equals("task_graph.replan")) return runtime.replan(context, call,
                    args.path("executionId").asText(), args.path("requestId").asText(), args.path("epoch").asLong(),
                    args.path("expectedRevision").asLong(), args.path("graph"));
            if (call.name().equals("test.write")) effects.add(call.callId().split(":")[1]);
            return new ToolResult(call.callId(), call.name(), true, "OK", Json.object().put("verified", true), true);
        }
    }
}
