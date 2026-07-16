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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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
                assertTrue(names.containsAll(List.of("world.observe", "movement.navigate", "movement.follow")));
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
                        ignored -> List.of("WithdrawFromStorage", "DepositToStorage", "CraftItem", "DeliverItem", "EatAndRecover"));
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
}
