package com.mccompanion.runtime.tool;

import com.mccompanion.protocol.CapabilitySet;
import com.mccompanion.protocol.CompanionBodyState;
import com.mccompanion.protocol.CompanionStatus;
import com.mccompanion.protocol.PositionDto;
import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.command.IdempotencyStore;
import com.mccompanion.runtime.command.ProtocolCommandSender;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.lease.LeaseService;
import com.mccompanion.runtime.logging.Redactor;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.Handshake;
import com.mccompanion.runtime.session.SessionPeer;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.task.TaskEventStore;
import com.mccompanion.runtime.task.TaskRecord;
import com.mccompanion.runtime.task.TaskRepository;
import com.mccompanion.runtime.task.TaskState;
import com.mccompanion.runtime.task.TaskType;
import com.mccompanion.runtime.taskgraph.TaskGraphExecutionRepository;
import com.mccompanion.runtime.taskgraph.TaskGraphRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

                ToolCall cancelCall = new ToolCall("external-cancel-request", "task_graph.cancel",
                        Json.object().put("executionId", executeCall.callId()));
                ToolResult cancelledTerminal = gateway.execute(context, cancelCall);
                assertEquals(cancelCall.callId(), cancelledTerminal.callId());

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

                ToolResult invalidToolSchema = gateway.execute(context,
                        new ToolCall("graph-6", "task_graph.validate",
                                Json.object().set("graph", Json.parse("""
                                        {"version":"mcac-task-graph/1","id":"invalid-tool-schema",
                                         "permissions":["MOVE","SHELL"],
                                         "root":{"id":"move","type":"call_tool","tool":"movement.navigate",
                                          "arguments":{"x":"wrong","y":64,"unexpected":true}}}
                                        """))));
                assertFalse(invalidToolSchema.success());
                String issues = invalidToolSchema.observation().path("issues").toString();
                assertTrue(issues.contains("TOOL_INPUT_SCHEMA_INVALID"), issues);
                assertTrue(issues.contains("UNKNOWN_PERMISSION"), issues);
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
    void boundedActionPrimitivesDispatchThroughExistingBodyExecutors() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("action-primitives.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("action-primitives.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            TaskRepository tasks = new TaskRepository(database, new TaskEventStore(database));
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                CapturingPeer peer = new CapturingPeer();
                var session = sessions.register(peer, new Handshake("mc-companion/1", "test", "1.21.1",
                        "fabric", "world", Json.object()));
                for (String companionId : List.of(
                        "c-step", "c-look", "c-stop", "c-idle", "c-break", "c-collect", "c-from", "c-to")) {
                    CompanionStatus status = new CompanionStatus(companionId, "owner", companionId, "world",
                            "minecraft:overworld", new PositionDto(10, 64, -5), CompanionBodyState.SPAWNED,
                            null, null, 0, 0, true, CapabilitySet.empty(), Instant.now());
                    sessions.registerCompanion(session, status, Json.object()
                            .put("dimension", "minecraft:overworld")
                            .set("position", Json.object().put("x", 10).put("y", 64).put("z", -5)));
                }
                CommandService commands = new CommandService(sessions, companions, tasks, new LeaseService(database),
                        new IdempotencyStore(database), new ProtocolCommandSender(), log);
                RuntimeToolGateway gateway = new RuntimeToolGateway(commands, companions, tasks,
                        ignored -> List.of("NavigateTo", "CollectResource", "MineResourceVein",
                                "WithdrawFromStorage", "DepositToStorage", "LookAt"));

                var definitions = gateway.definitions(new ToolContext("hermes", "session", "c-step"));
                assertTrue(definitions.stream().map(ToolDefinition::name).toList().containsAll(List.of(
                        "movement.step", "movement.stop", "block.break", "entity.collect", "inventory.transfer")));
                assertTrue(definitions.stream().map(ToolDefinition::name).toList().contains("movement.look"));
                assertEquals("MOVE", definition(definitions, "movement.step").permission());
                assertEquals("MOVE", definition(definitions, "movement.look").permission());
                assertEquals("MINE", definition(definitions, "block.break").permission());
                assertEquals("COLLECT", definition(definitions, "entity.collect").permission());
                assertEquals("INVENTORY", definition(definitions, "inventory.transfer").permission());
                assertEquals(List.of("item", "quantity"), required(definition(definitions, "entity.collect")));

                ToolResult stepped = gateway.execute(new ToolContext("hermes", "session", "c-step"),
                        new ToolCall("step", "movement.step", Json.object().put("dx", 2).put("dy", -1).put("dz", 3)));
                assertTrue(stepped.success(), stepped.observation().toString());
                var stepCommand = peer.lastCommand();
                assertEquals("travel", stepCommand.path("arguments").path("behaviorType").asText());
                assertEquals(12, stepCommand.path("arguments").path("parameters").path("target").path("x").asInt());
                assertEquals(63, stepCommand.path("arguments").path("parameters").path("target").path("y").asInt());
                assertEquals(-2, stepCommand.path("arguments").path("parameters").path("target").path("z").asInt());

                ToolResult looked = gateway.execute(new ToolContext("hermes", "session", "c-look"),
                        new ToolCall("look", "movement.look",
                                Json.object().put("dimension", "minecraft:overworld")
                                        .put("x", 14).put("y", 66).put("z", -1)));
                assertTrue(looked.success(), looked.observation().toString());
                var lookParameters = peer.lastCommand().path("arguments").path("parameters");
                assertEquals("LookAt", lookParameters.path("capability").asText());
                assertEquals(14, lookParameters.path("parameters").path("target").path("x").asInt());
                assertEquals(66, lookParameters.path("parameters").path("target").path("y").asInt());
                assertEquals(-1, lookParameters.path("parameters").path("target").path("z").asInt());

                ToolResult moving = gateway.execute(new ToolContext("hermes", "session", "c-stop"),
                        new ToolCall("move-before-stop", "movement.navigate",
                                Json.object().put("x", 12).put("y", 64).put("z", -5)));
                assertTrue(moving.success(), moving.observation().toString());
                ToolResult stopped = gateway.execute(new ToolContext("hermes", "session", "c-stop"),
                        new ToolCall("stop-moving", "movement.stop", Json.object()));
                assertTrue(stopped.success(), stopped.observation().toString());
                assertEquals("cancel_behavior", peer.lastCommand().path("command").asText());

                ToolResult broken = gateway.execute(new ToolContext("hermes", "session", "c-break"),
                        new ToolCall("break", "block.break", Json.object().put("block", "examplemod:blue_ore")
                                .set("position", Json.object().put("dimension", "examplemod:moon")
                                        .put("x", 3).put("y", 70).put("z", 4))));
                assertTrue(broken.success(), broken.observation().toString());
                var breakParameters = peer.lastCommand().path("arguments").path("parameters");
                assertEquals("MineResourceVein", breakParameters.path("capability").asText());
                assertEquals(1, breakParameters.path("parameters").path("quantity").asInt());
                assertEquals("examplemod:blue_ore", breakParameters.path("parameters").path("item").asText());
                assertEquals("examplemod:moon",
                        breakParameters.path("parameters").path("target").path("dimension").asText());

                ToolResult collected = gateway.execute(new ToolContext("hermes", "session", "c-collect"),
                        new ToolCall("collect", "entity.collect",
                                Json.object().put("item", "examplemod:blue_gem").put("quantity", 2)));
                assertTrue(collected.success(), collected.observation().toString());
                assertEquals("CollectResource", peer.lastCommand().path("arguments").path("parameters")
                        .path("capability").asText());

                executeTransfer(gateway, peer, "c-from", "FROM_CONTAINER", "WithdrawFromStorage");
                executeTransfer(gateway, peer, "c-to", "TO_CONTAINER", "DepositToStorage");

                ToolResult zeroStep = gateway.execute(new ToolContext("hermes", "session", "c-step"),
                        new ToolCall("zero-step", "movement.step",
                                Json.object().put("dx", 0).put("dy", 0).put("dz", 0)));
                assertFalse(zeroStep.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", zeroStep.code());
                ToolResult invalidDirection = gateway.execute(new ToolContext("hermes", "session", "c-to"),
                        new ToolCall("invalid-transfer", "inventory.transfer", Json.object()
                                .put("direction", "ARBITRARY").put("item", "minecraft:stone").put("quantity", 1)
                                .set("container", Json.object().put("x", 1).put("y", 64).put("z", 1))));
                assertFalse(invalidDirection.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", invalidDirection.code());
                ToolResult noMovement = gateway.execute(new ToolContext("hermes", "session", "c-idle"),
                        new ToolCall("stop-idle", "movement.stop", Json.object()));
                assertFalse(noMovement.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", noMovement.code());
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
                    assertEquals(immediate == TaskState.PAUSED ? "BLOCKED" : "RECONCILIATION_REQUIRED",
                            immediateResult.observation().path("state").asText());
                    if (immediate == TaskState.RECONCILIATION_REQUIRED) {
                        assertEquals("TOOL_RECONCILIATION_REQUIRED", immediateResult.code());
                    }
                }
            }
        }
    }

    @Test
    void unconfirmedMinecraftToolTimeoutPersistsReconciliationState() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("timeout-reconciliation.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("timeout-reconciliation.log"),
                     false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            TaskRepository tasks = new TaskRepository(database, new TaskEventStore(database));
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log)) {
                CommandService commands = new CommandService(sessions, companions, tasks,
                        new LeaseService(database), new IdempotencyStore(database),
                        new ProtocolCommandSender(), log);
                RuntimeToolGateway gateway = new RuntimeToolGateway(commands, companions, tasks,
                        ignored -> List.of("NavigateTo"), Duration.ofMillis(100));
                var task = tasks.create("c-timeout", TaskType.TRAVEL, "timeout", Json.object());
                task = tasks.transition(task.taskId(), task.revision(), TaskState.ACCEPTED,
                        "CommandAccepted", Json.object());
                ToolCall call = new ToolCall("navigate-timeout", "movement.navigate", Json.object());
                ToolResult accepted = new ToolResult(call.callId(), call.name(), true,
                        "COMMAND_DISPATCHED", Json.object().put("taskId", task.taskId()), false);

                ToolResult result = gateway.awaitTerminal(
                        new ToolContext("hermes", "session-timeout", "c-timeout"),
                        call, accepted, Duration.ofMillis(25), ignored -> { });

                assertFalse(result.success());
                assertEquals("TOOL_TIMEOUT_RECONCILIATION_REQUIRED", result.code());
                assertEquals("RECONCILIATION_REQUIRED",
                        result.observation().path("state").asText());
                assertTrue(result.observation().path("timedOut").asBoolean());
                assertFalse(result.observation().path("cancellationConfirmed").asBoolean());
                TaskRecord persisted = tasks.get(task.taskId()).orElseThrow();
                assertEquals(TaskState.RECONCILIATION_REQUIRED, persisted.state());
                assertEquals("ToolTimeoutCancellationUnconfirmed",
                        tasks.events(task.taskId()).getLast().eventType());
            }
        }
    }

    private static ToolDefinition definition(List<ToolDefinition> definitions, String name) {
        return definitions.stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> required(ToolDefinition definition) {
        return java.util.stream.StreamSupport.stream(
                definition.inputSchema().path("required").spliterator(), false)
                .map(com.fasterxml.jackson.databind.JsonNode::asText).toList();
    }

    private static void executeTransfer(RuntimeToolGateway gateway, CapturingPeer peer, String companionId,
                                        String direction, String expectedCapability) {
        ToolResult result = gateway.execute(new ToolContext("hermes", "session", companionId),
                new ToolCall("transfer-" + companionId, "inventory.transfer", Json.object()
                        .put("direction", direction).put("item", "examplemod:blue_gem").put("quantity", 3)
                        .set("container", Json.object().put("dimension", "examplemod:moon")
                                .put("x", 4).put("y", 70).put("z", 5))));
        assertTrue(result.success(), result.observation().toString());
        var parameters = peer.lastCommand().path("arguments").path("parameters");
        assertEquals(expectedCapability, parameters.path("capability").asText());
        assertFalse(parameters.path("parameters").has("direction"));
        assertEquals("examplemod:blue_gem", parameters.path("parameters").path("item").asText());
    }

    private static final class CapturingPeer implements SessionPeer {
        private final List<String> messages = new ArrayList<>();
        @Override public String id() { return "primitive-peer"; }
        @Override public String remoteAddress() { return "loopback"; }
        @Override public boolean isOpen() { return true; }
        @Override public void send(String text) { messages.add(text); }
        @Override public void close(int code, String reason) { }

        private com.fasterxml.jackson.databind.JsonNode lastCommand() {
            return Json.parse(messages.getLast()).path("payload");
        }
    }
}
