package com.mccompanion.runtime.tool;

import com.mccompanion.protocol.CapabilitySet;
import com.mccompanion.protocol.CompanionBodyState;
import com.mccompanion.protocol.CompanionStatus;
import com.mccompanion.protocol.PositionDto;
import com.mccompanion.runtime.command.ProtocolCommandSender;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.Redactor;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.Handshake;
import com.mccompanion.runtime.session.RuntimeSession;
import com.mccompanion.runtime.session.SessionPeer;
import com.mccompanion.runtime.session.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistryToolGatewayTest {
    @TempDir Path temporary;

    @Test
    void dispatchesBoundedLiveQueriesAndEnforcesSessionBinding() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("registry-tools.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("registry-tools.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log);
                 RegistryToolGateway gateway = new RegistryToolGateway(sessions, new ProtocolCommandSender())) {
                CapturingPeer peer = new CapturingPeer("registry-peer");
                RuntimeSession session = register(sessions, peer, "world", "c1", true);
                ToolContext context = new ToolContext("hermes", "brain-session", "c1");

                List<ToolDefinition> definitions = gateway.definitions(context);
                assertEquals(List.of("registry.search", "registry.describe", "recipe.query",
                                "block.inspect", "item.inspect", "entity.inspect", "menu.inspect"),
                        definitions.stream().map(ToolDefinition::name).toList());
                assertEquals("INVENTORY", definitions.stream()
                        .filter(value -> value.name().equals("item.inspect")).findFirst().orElseThrow().permission());

                ToolCall search = new ToolCall("search-1", "registry.search",
                        Json.object().put("kind", "ITEM").put("namespace", "examplemod")
                                .put("query", "blue").put("limit", 8));
                ToolResult accepted = gateway.execute(context, search);
                assertTrue(accepted.success());
                assertFalse(accepted.terminal());
                var command = peer.lastPayload();
                assertEquals("query_registry", command.path("command").asText());
                assertEquals("examplemod", command.path("arguments").path("namespace").asText());
                String queryId = command.path("arguments").path("queryId").asText();
                assertFalse(queryId.isBlank());

                assertTrue(gateway.complete(session, Json.object().put("queryId", queryId)
                        .put("companionId", "c1").put("success", true).put("code", "OK")
                        .set("observation", Json.object().put("source", "LIVE_SERVER_REGISTRY")
                                .put("totalMatches", 1).put("truncated", false)
                                .set("entries", Json.MAPPER.createArrayNode().add(Json.object()
                                        .put("kind", "ITEM").put("id", "examplemod:blue_gem")
                                        .put("namespace", "examplemod").put("path", "blue_gem"))))));
                ToolResult completed = gateway.awaitTerminal(context, search, accepted,
                        Duration.ofSeconds(1), ignored -> { });
                assertTrue(completed.success());
                assertTrue(completed.terminal());
                assertEquals("examplemod:blue_gem",
                        completed.observation().path("entries").path(0).path("id").asText());

                ToolCall recipe = new ToolCall("recipe-1", "recipe.query",
                        Json.object().put("type", "ANY").put("query", "oak_planks")
                                .put("output", "minecraft:oak_planks").put("limit", 4));
                ToolResult recipeAccepted = gateway.execute(context, recipe);
                assertFalse(recipeAccepted.terminal());
                assertEquals("query_recipe", peer.lastPayload().path("command").asText());
                String recipeQueryId = peer.lastPayload().path("arguments").path("queryId").asText();

                CapturingPeer otherPeer = new CapturingPeer("other-peer");
                RuntimeSession other = register(sessions, otherPeer, "world-2", "c2", true);
                assertThrows(IllegalArgumentException.class, () -> gateway.complete(other,
                        Json.object().put("queryId", recipeQueryId).put("companionId", "c1")
                                .put("success", true).put("code", "OK")
                                .set("observation", Json.object().put("source", "LIVE_SERVER_RECIPE_MANAGER"))));
                gateway.cancel(context, recipe.callId(), "owner cancelled");
                ToolResult cancelled = gateway.awaitTerminal(context, recipe, recipeAccepted,
                        Duration.ofSeconds(1), ignored -> { });
                assertFalse(cancelled.success());
                assertEquals("QUERY_CANCELLED", cancelled.code());

                ToolCall block = new ToolCall("block-1", "block.inspect",
                        Json.object().set("position", Json.object().put("dimension", "examplemod:moon")
                                .put("x", 2).put("y", 70).put("z", -3)));
                ToolResult blockAccepted = gateway.execute(context, block);
                assertFalse(blockAccepted.terminal());
                assertEquals("query_observation", peer.lastPayload().path("command").asText());
                assertEquals("block.inspect", peer.lastPayload().path("arguments").path("tool").asText());
                String blockQueryId = peer.lastPayload().path("arguments").path("queryId").asText();
                assertTrue(gateway.complete(session, Json.object().put("queryId", blockQueryId)
                        .put("companionId", "c1").put("success", true).put("code", "OK")
                        .set("observation", Json.object().put("source", "LIVE_SERVER_OBSERVATION")
                                .put("kind", "BLOCK").put("block", "examplemod:moon_ore"))));
                ToolResult inspected = gateway.awaitTerminal(context, block, blockAccepted,
                        Duration.ofSeconds(1), ignored -> { });
                assertEquals("examplemod:moon_ore", inspected.observation().path("block").asText());

                ToolCall menu = new ToolCall("menu-1", "menu.inspect", Json.object());
                ToolResult menuAccepted = gateway.execute(context, menu);
                assertFalse(menuAccepted.terminal());
                assertEquals("query_observation", peer.lastPayload().path("command").asText());
                assertEquals("menu.inspect", peer.lastPayload().path("arguments").path("tool").asText());
                String menuQueryId = peer.lastPayload().path("arguments").path("queryId").asText();
                assertTrue(gateway.complete(session, Json.object().put("queryId", menuQueryId)
                        .put("companionId", "c1").put("success", true).put("code", "OK")
                        .set("observation", Json.object().put("source", "LIVE_SERVER_OBSERVATION")
                                .put("kind", "MENU")
                                .put("sessionToken", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                                .put("containerId", 7))));
                ToolResult menuInspected = gateway.awaitTerminal(context, menu, menuAccepted,
                        Duration.ofSeconds(1), ignored -> { });
                assertEquals(7, menuInspected.observation().path("containerId").asInt());

                ToolResult invalid = gateway.execute(context, new ToolCall("bad", "registry.describe",
                        Json.object().put("kind", "ITEM").put("id", "../server.properties")));
                assertFalse(invalid.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", invalid.code());
                assertEquals("INVALID_TOOL_ARGUMENTS", gateway.execute(context,
                        new ToolCall("bad-radius", "entity.inspect",
                                Json.object().put("radius", 64))).code());
            }
        }
    }

    @Test
    void hidesQueriesWhenTheConnectedModDoesNotAdvertiseThem() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("registry-hidden.db"));
             RuntimeLog log = new RuntimeLog(temporary.resolve("registry-hidden.log"), false, new Redactor())) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            try (SessionRegistry sessions = new SessionRegistry(database, companions, log);
                 RegistryToolGateway gateway = new RegistryToolGateway(sessions, new ProtocolCommandSender())) {
                register(sessions, new CapturingPeer("legacy-peer"), "legacy-world", "legacy", false);
                ToolContext context = new ToolContext("hermes", "brain-session", "legacy");
                assertTrue(gateway.definitions(context).isEmpty());
                ToolResult rejected = gateway.execute(context, new ToolCall("search", "registry.search",
                        Json.object().put("kind", "ITEM")));
                assertEquals("TOOL_UNAVAILABLE", rejected.code());
            }
        }
    }

    private static RuntimeSession register(SessionRegistry sessions, CapturingPeer peer, String world,
                                           String companionId, boolean queries) throws Exception {
        var capabilities = Json.object();
        if (queries) capabilities.put("registry_query", true).put("recipe_query", true)
                .put("primitive_observation_query", true);
        RuntimeSession session = sessions.register(peer,
                new Handshake("mc-companion/1", "test", "1.21.1", "fabric", world, capabilities));
        CompanionStatus status = new CompanionStatus(companionId, "owner-" + companionId, companionId, world,
                "minecraft:overworld", new PositionDto(0, 64, 0), CompanionBodyState.SPAWNED,
                null, null, 0, 0, true, CapabilitySet.empty(), Instant.now());
        sessions.registerCompanion(session, status, Json.object().put("dimension", "minecraft:overworld"));
        return session;
    }

    private static final class CapturingPeer implements SessionPeer {
        private final String id;
        private final List<String> messages = new ArrayList<>();

        private CapturingPeer(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public String remoteAddress() { return "loopback"; }
        @Override public boolean isOpen() { return true; }
        @Override public void send(String text) { messages.add(text); }
        @Override public void close(int code, String reason) { }

        private com.fasterxml.jackson.databind.JsonNode lastPayload() {
            return Json.parse(messages.getLast()).path("payload");
        }
    }
}
