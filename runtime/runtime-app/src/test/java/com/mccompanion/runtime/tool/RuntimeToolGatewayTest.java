package com.mccompanion.runtime.tool;

import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.command.IdempotencyStore;
import com.mccompanion.runtime.command.ProtocolCommandSender;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.lease.LeaseService;
import com.mccompanion.runtime.logging.Redactor;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.task.TaskEventStore;
import com.mccompanion.runtime.task.TaskRepository;
import com.mccompanion.runtime.task.TaskState;
import com.mccompanion.runtime.task.TaskType;
import com.mccompanion.runtime.taskgraph.TaskGraphExecutionRepository;
import com.mccompanion.runtime.taskgraph.TaskGraphRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeToolGatewayTest {
    @TempDir Path temporary;

    @Test
    void exposesOnlyAvailableToolsAndReturnsVerifiedWorldObservation() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("gateway.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("gateway.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            companions.upsert("c1", "session", "world", "owner", "Misuzu",
                    Json.object().put("health", 18).put("dimension", "minecraft:overworld"));
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                CommandService commands = new CommandService(sessions, companions,
                        new TaskRepository(database, new TaskEventStore(database)), new LeaseService(database),
                        new IdempotencyStore(database), new ProtocolCommandSender(), log);
                RuntimeToolGateway gateway = new RuntimeToolGateway(commands, companions,
                        ignored -> List.of("NavigateTo", "FollowOwner"));
                ToolContext context = new ToolContext("hermes", "brain-session", "c1");

                var names = gateway.definitions(context).stream().map(ToolDefinition::name).toList();
                assertTrue(names.containsAll(List.of("world.observe", "movement.navigate", "movement.follow",
                        "task_graph.validate", "task_graph.execute")));
                assertFalse(names.contains("inventory.withdraw"));
                ToolResult observed = gateway.execute(context,
                        new ToolCall("observe-1", "world.observe", Json.object()));
                assertTrue(observed.success());
                assertEquals(18, observed.observation().path("health").asInt());
                ToolResult injected = gateway.execute(context,
                        new ToolCall("observe-2", "world.observe", Json.object().put("shell", "whoami")));
                assertFalse(injected.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", injected.code());
                ToolResult unavailable = gateway.execute(context,
                        new ToolCall("withdraw-1", "inventory.withdraw", Json.object()));
                assertFalse(unavailable.success());
                assertEquals("TOOL_UNAVAILABLE", unavailable.code());
            }
        }
    }

    @Test
    void validatesExternalTaskGraphsWithoutExecutingThem() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("graph.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("graph.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                CommandService commands = new CommandService(sessions, companions,
                        new TaskRepository(database, new TaskEventStore(database)), new LeaseService(database),
                        new IdempotencyStore(database), new ProtocolCommandSender(), log);
                RuntimeToolGateway gateway = new RuntimeToolGateway(commands, companions,
                        ignored -> List.of("NavigateTo"));
                gateway.attachTaskGraphRuntime(new TaskGraphRuntime(gateway,
                        new TaskGraphExecutionRepository(database)));
                ToolContext context = new ToolContext("hermes", "brain-session", "c1");
                var graph = Json.parse("""
                        {"version":"mcac-task-graph/1","id":"navigate-after-observe",
                         "permissions":["READ_WORLD","MOVE"],
                         "root":{"id":"root","type":"sequence","nodes":[
                           {"id":"observe","type":"call_tool","tool":"world.observe"},
                           {"id":"move","type":"call_tool","tool":"movement.navigate",
                            "arguments":{"x":1,"y":64,"z":1}}
                         ]}}
                        """);
                ToolResult valid = gateway.execute(context, new ToolCall("graph-1", "task_graph.validate",
                        Json.object().set("graph", graph)));
                assertTrue(valid.success(), valid.observation().toString());
                assertTrue(valid.observation().path("valid").asBoolean());

                ((com.fasterxml.jackson.databind.node.ObjectNode) graph.path("root").path("nodes").path(1))
                        .put("tool", "shell.execute");
                ToolResult invalid = gateway.execute(context, new ToolCall("graph-2", "task_graph.validate",
                        Json.object().set("graph", graph)));
                assertFalse(invalid.success());
                assertEquals("TASK_GRAPH_INVALID", invalid.code());

                companions.upsert("c1", "session", "world", "owner", "Misuzu",
                        Json.object().put("health", 20));
                ToolCall executeCall = new ToolCall("graph-3", "task_graph.execute",
                        Json.object().put("format", "yaml").put("document", """
                                version: mcac-task-graph/1
                                id: observe-only
                                permissions: [READ_WORLD]
                                root:
                                  id: root
                                  type: sequence
                                  nodes:
                                    - {id: observe, type: call_tool, tool: world.observe}
                                    - {id: done, type: return, value: observed}
                                """));
                ToolResult accepted = gateway.execute(context, executeCall);
                assertFalse(accepted.terminal());
                ToolResult executed = gateway.awaitTerminal(context, executeCall, accepted,
                        Duration.ofSeconds(2), ignored -> { });
                assertTrue(executed.success(), executed.observation().toString());
                assertEquals(20, executed.observation().path("outputs").path("observe").path("health").asInt());

                ToolResult unsupported = gateway.execute(context, new ToolCall("graph-4", "task_graph.validate",
                        Json.object().set("graph", Json.parse("""
                                {"version":"mcac-task-graph/1","id":"future-node","permissions":[],
                                 "root":{"id":"ask","type":"ask_user","prompt":"Continue?"}}
                                """))));
                assertFalse(unsupported.success());
                assertTrue(unsupported.observation().path("issues").toString().contains("NODE_NOT_EXECUTABLE"));

                ToolResult missingPermission = gateway.execute(context, new ToolCall("graph-5", "task_graph.validate",
                        Json.object().set("graph", Json.parse("""
                                {"version":"mcac-task-graph/1","id":"missing-permission","permissions":[],
                                 "root":{"id":"observe","type":"call_tool","tool":"world.observe"}}
                                """))));
                assertFalse(missingPermission.success());
                assertTrue(missingPermission.observation().path("issues").toString()
                        .contains("TOOL_PERMISSION_NOT_DECLARED"));
                gateway.close();
            }
        }
    }

    @Test
    void inventorySchemasAreBoundedAndUnexpectedArgumentsAreRejected() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("inventory.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("inventory.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                CommandService commands = new CommandService(sessions, companions,
                        new TaskRepository(database, new TaskEventStore(database)), new LeaseService(database),
                        new IdempotencyStore(database), new ProtocolCommandSender(), log);
                RuntimeToolGateway gateway = new RuntimeToolGateway(commands, companions,
                        ignored -> List.of("WithdrawFromStorage", "DepositToStorage", "CraftItem", "DeliverItem",
                                "EatAndRecover", "CollectResource", "MineResourceVein", "SmeltItem", "DefendOwner"));
                ToolContext context = new ToolContext("hermes", "brain-session", "c1");
                ToolDefinition withdraw = gateway.definitions(context).stream()
                        .filter(value -> value.name().equals("inventory.withdraw")).findFirst().orElseThrow();
                assertEquals(List.of("item", "quantity", "container"),
                        java.util.stream.StreamSupport.stream(withdraw.inputSchema().path("required").spliterator(), false)
                                .map(com.fasterxml.jackson.databind.JsonNode::asText).toList());
                ToolDefinition deposit = gateway.definitions(context).stream()
                        .filter(value -> value.name().equals("inventory.deposit")).findFirst().orElseThrow();
                assertEquals(withdraw.inputSchema(), deposit.inputSchema());
                ToolDefinition craft = gateway.definitions(context).stream()
                        .filter(value -> value.name().equals("item.craft")).findFirst().orElseThrow();
                assertEquals(List.of("item", "quantity"),
                        java.util.stream.StreamSupport.stream(craft.inputSchema().path("required").spliterator(), false)
                                .map(com.fasterxml.jackson.databind.JsonNode::asText).toList());
                ToolDefinition collect = gateway.definitions(context).stream()
                        .filter(value -> value.name().equals("resource.collect")).findFirst().orElseThrow();
                assertEquals(List.of("item", "quantity"), java.util.stream.StreamSupport.stream(
                        collect.inputSchema().path("required").spliterator(), false)
                        .map(com.fasterxml.jackson.databind.JsonNode::asText).toList());
                ToolDefinition mine = gateway.definitions(context).stream()
                        .filter(value -> value.name().equals("resource.mine_vein")).findFirst().orElseThrow();
                assertEquals(List.of("block", "maxBlocks", "origin"), java.util.stream.StreamSupport.stream(
                        mine.inputSchema().path("required").spliterator(), false)
                        .map(com.fasterxml.jackson.databind.JsonNode::asText).toList());
                ToolResult invalidMine = gateway.execute(context, new ToolCall("mine-1", "resource.mine_vein",
                        Json.object().put("block", "minecraft:diamond_ore").put("maxBlocks", 33)
                                .set("origin", Json.object().put("x", 1).put("y", 64).put("z", 1))));
                assertFalse(invalidMine.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", invalidMine.code());
                ToolDefinition smelt = gateway.definitions(context).stream()
                        .filter(value -> value.name().equals("item.smelt")).findFirst().orElseThrow();
                assertEquals(List.of("item", "quantity", "station"), java.util.stream.StreamSupport.stream(
                        smelt.inputSchema().path("required").spliterator(), false)
                        .map(com.fasterxml.jackson.databind.JsonNode::asText).toList());
                ToolResult invalidSmelt = gateway.execute(context, new ToolCall("smelt-1", "item.smelt",
                        Json.object().put("item", "minecraft:iron_ingot").put("quantity", 65)
                                .set("station", Json.object().put("x", 1).put("y", 64).put("z", 1))));
                assertFalse(invalidSmelt.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", invalidSmelt.code());
                ToolDefinition defend = gateway.definitions(context).stream()
                        .filter(value -> value.name().equals("combat.defend_owner")).findFirst().orElseThrow();
                assertEquals(0, defend.inputSchema().path("properties").size());
                ToolResult invalidStation = gateway.execute(context, new ToolCall("craft-1", "item.craft",
                        Json.object().put("item", "minecraft:iron_pickaxe").put("quantity", 1)
                                .set("station", Json.object().put("x", 1).put("y", 64))));
                assertFalse(invalidStation.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", invalidStation.code());

                ToolResult rejected = gateway.execute(context, new ToolCall("withdraw-1", "inventory.withdraw",
                        Json.object().put("item", "minecraft:iron_ingot").put("quantity", 1)
                                .put("url", "https://example.invalid")));
                assertFalse(rejected.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", rejected.code());
            }
        }
    }

    @Test
    void rejectsUnsafeCoordinatesBeforeAnyMinecraftCommand() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("invalid.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("invalid.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                CommandService commands = new CommandService(sessions, companions,
                        new TaskRepository(database, new TaskEventStore(database)), new LeaseService(database),
                        new IdempotencyStore(database), new ProtocolCommandSender(), log);
                RuntimeToolGateway gateway = new RuntimeToolGateway(commands, companions,
                        ignored -> List.of("NavigateTo"));
                ToolResult result = gateway.execute(new ToolContext("hermes", "brain-session", "c1"),
                        new ToolCall("navigate-1", "movement.navigate",
                                Json.object().put("x", 40_000_000).put("y", 64).put("z", 0)));
                assertFalse(result.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", result.code());
            }
        }
    }

    @Test
    void worldScanSchemaIsBoundedBeforeDispatch() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("scan.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("scan.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                CommandService commands = new CommandService(sessions, companions,
                        new TaskRepository(database, new TaskEventStore(database)), new LeaseService(database),
                        new IdempotencyStore(database), new ProtocolCommandSender(), log);
                RuntimeToolGateway gateway = new RuntimeToolGateway(commands, companions, ignored -> List.of("ExploreArea"));
                ToolContext context = new ToolContext("hermes", "brain-session", "c1");
                ToolDefinition scan = gateway.definitions(context).stream()
                        .filter(value -> value.name().equals("world.scan")).findFirst().orElseThrow();
                assertEquals(List.of("block", "radius"), java.util.stream.StreamSupport.stream(
                        scan.inputSchema().path("required").spliterator(), false)
                        .map(com.fasterxml.jackson.databind.JsonNode::asText).toList());
                ToolResult rejected = gateway.execute(context, new ToolCall("scan-1", "world.scan",
                        Json.object().put("block", "minecraft:diamond_ore").put("radius", 17)));
                assertFalse(rejected.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", rejected.code());
            }
        }
    }

    @Test
    void waitsForDurableTaskTerminalStateAndReturnsLastFabricObservation() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("terminal.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("terminal.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            TaskRepository tasks = new TaskRepository(database, new TaskEventStore(database));
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                CommandService commands = new CommandService(sessions, companions, tasks, new LeaseService(database),
                        new IdempotencyStore(database), new ProtocolCommandSender(), log);
                RuntimeToolGateway gateway = new RuntimeToolGateway(commands, companions, tasks,
                        ignored -> List.of("NavigateTo"));
                var task = tasks.create("c1", TaskType.TRAVEL, "go", Json.object());
                task = tasks.transition(task.taskId(), task.revision(), TaskState.ACCEPTED,
                        "CommandAccepted", Json.object());
                var acceptedTask = task;
                CompletableFuture<Void> fabric = CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(50);
                        var running = tasks.transition(acceptedTask.taskId(), acceptedTask.revision(), TaskState.RUNNING,
                                "BehaviorStarted", Json.object().put("running", true));
                        Thread.sleep(50);
                        tasks.transition(running.taskId(), running.revision(), TaskState.COMPLETED,
                                "BehaviorCompleted", Json.object().put("arrived", true).put("verifiedBy", "fabric"));
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                });
                ToolCall call = new ToolCall("navigate-1", "movement.navigate", Json.object());
                ToolResult accepted = new ToolResult(call.callId(), call.name(), true, "COMMAND_DISPATCHED",
                        Json.object().put("state", "ACCEPTED").put("taskId", task.taskId())
                                .put("behaviorId", task.behaviorId()), false);
                List<String> progressStates = new ArrayList<>();
                ToolResult result = gateway.awaitTerminal(new ToolContext("hermes", "session-1", "c1"),
                        call, accepted, Duration.ofSeconds(2),
                        progress -> progressStates.add(progress.observation().path("state").asText()));
                fabric.get(2, TimeUnit.SECONDS);
                assertTrue(result.success());
                assertTrue(result.terminal());
                assertEquals("SUCCEEDED", result.observation().path("state").asText());
                assertTrue(result.observation().path("fabricObservation").path("arrived").asBoolean());
                assertEquals("fabric", result.observation().path("fabricObservation").path("verifiedBy").asText());
                assertTrue(progressStates.contains("ACCEPTED"));
                assertTrue(progressStates.contains("RUNNING"));

                var blockedTask = tasks.create("c2", TaskType.TRAVEL, "blocked", Json.object());
                blockedTask = tasks.transition(blockedTask.taskId(), blockedTask.revision(), TaskState.ACCEPTED,
                        "CommandAccepted", Json.object());
                blockedTask = tasks.transition(blockedTask.taskId(), blockedTask.revision(), TaskState.BLOCKED,
                        "BehaviorBlocked", Json.object().put("code", "PATH_BLOCKED"));
                ToolCall blockedCall = new ToolCall("navigate-blocked", "movement.navigate", Json.object());
                ToolResult blockedAccepted = new ToolResult(blockedCall.callId(), blockedCall.name(), true,
                        "COMMAND_DISPATCHED", Json.object().put("taskId", blockedTask.taskId()), false);
                ToolResult blocked = gateway.awaitTerminal(new ToolContext("hermes", "session-2", "c2"),
                        blockedCall, blockedAccepted, Duration.ofSeconds(2), ignored -> { });
                assertTrue(blocked.terminal());
                assertFalse(blocked.success());
                assertEquals("TOOL_BLOCKED", blocked.code());
                assertEquals("BLOCKED", blocked.observation().path("state").asText());

                for (TaskState immediate : List.of(TaskState.PAUSED, TaskState.RECONCILIATION_REQUIRED)) {
                    String id = immediate.name().toLowerCase(java.util.Locale.ROOT);
                    var waitingTask = tasks.create("c-" + id, TaskType.TRAVEL, id, Json.object());
                    waitingTask = tasks.transition(waitingTask.taskId(), waitingTask.revision(), TaskState.ACCEPTED,
                            "CommandAccepted", Json.object());
                    waitingTask = tasks.transition(waitingTask.taskId(), waitingTask.revision(), immediate,
                            "Behavior" + immediate, Json.object().put("state", immediate.name()));
                    ToolCall waitingCall = new ToolCall("call-" + id, "movement.navigate", Json.object());
                    ToolResult waitingAccepted = new ToolResult(waitingCall.callId(), waitingCall.name(), true,
                            "COMMAND_DISPATCHED", Json.object().put("taskId", waitingTask.taskId()), false);
                    long started = System.nanoTime();
                    ToolResult immediateResult = gateway.awaitTerminal(new ToolContext("hermes", "session-" + id,
                                    "c-" + id), waitingCall, waitingAccepted, Duration.ofSeconds(5), ignored -> { });
                    assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0,
                            immediate + " waited for the tool timeout");
                    assertTrue(immediateResult.terminal());
                    assertEquals(immediate == TaskState.PAUSED ? "BLOCKED" : "INTERRUPTED",
                            immediateResult.observation().path("state").asText());
                }
            }
        }
    }
}
