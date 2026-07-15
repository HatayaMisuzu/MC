package com.mccompanion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.brain.BrainTurnResult;
import com.mccompanion.runtime.brain.ReplayBrainAdapter;
import com.mccompanion.runtime.config.RuntimeConfig;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.search.ReplaySearchProvider;
import com.mccompanion.runtime.search.SearchPage;
import com.mccompanion.runtime.search.SearchSource;
import com.mccompanion.runtime.tool.ToolCall;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeApplicationTest {
    @TempDir Path temporary;

    @Test
    void startsWithoutApiKeyAcceptsAuthenticatedSessionAndShutsDown() throws Exception {
        RuntimeConfig config = RuntimeConfig.defaults(temporary);
        config.server.port = 0;
        config.logging.console = false;
        config.provider.mode = "rules";
        try (RuntimeApplication application = RuntimeApplication.start(config, false)) {
            assertTrue(application.port() > 0);
            assertEquals("wal", new com.mccompanion.runtime.db.RuntimeDatabase(config.databasePath())
                    .journalMode().toLowerCase());
            String token = Files.readString(config.tokenPath()).trim();
            TestClient client = new TestClient(new URI("ws://127.0.0.1:" + application.port()));
            assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
            client.send("""
                    {"type":"hello","protocol":"mc-companion/1","token":"%s",
                     "modVersion":"0.1.0-alpha","minecraftVersion":"1.21.1","loader":"fabric",
                     "worldId":"world-test","capabilities":{"NavigateTo":true,"FollowOwner":true,
                     "DeliverItem":true,"EatAndRecover":true,"CraftItem":true}}
                    """.formatted(token));
            JsonNode hello = client.awaitType("hello_ack", 5);
            assertTrue(hello.path("accepted").asBoolean());
            String sessionId = hello.path("sessionId").asText();
            client.send("""
                    {"type":"companion_status","sessionId":"%s","sequence":0,"payload":{
                      "companionId":"companion-1","ownerId":"owner-1","displayName":"Test Companion",
                      "worldId":"world-test","dimension":"minecraft:overworld",
                      "position":{"x":0,"y":64,"z":0},"bodyState":"spawned",
                      "behaviorRevision":0,"controlEpoch":0,"runtimeConnected":true,
                      "capabilities":{},"observedAt":"%s"}}
                    """.formatted(sessionId, Instant.now()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (application.companions().get("companion-1").isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals("Test Companion", application.companions().get("companion-1").orElseThrow().displayName());
            client.send("""
                    {"type":"player_request","sessionId":"%s","sequence":1,"payload":{
                      "requestId":"request-1","companionId":"companion-1","ownerId":"owner-1",
                      "text":"状态"}}
                    """.formatted(sessionId));
            JsonNode playerReply = client.awaitType("player_reply", 5);
            assertTrue(playerReply.path("payload").path("accepted").asBoolean());
            assertEquals("RESPOND", playerReply.path("payload").path("decision").asText());
            assertFalse(playerReply.path("payload").path("reply").asText().isBlank());
            assertEquals("AVAILABLE_NOW", playerReply.path("payload").path("capabilityStates")
                    .path("NavigateTo").path("state").asText());
            assertEquals("DECLARED", playerReply.path("payload").path("capabilityStates")
                    .path("CraftItem").path("state").asText());
            assertTrue(application.plans().activeForCompanion("companion-1").isEmpty(),
                    "a conversational status response must not create an agent plan");
            assertTrue(application.commands().activeTaskFor("companion-1").isEmpty(),
                    "a conversational status response must not start a Minecraft task");
            var conversationDatabase = new com.mccompanion.runtime.db.RuntimeDatabase(config.databasePath());
            var transcript = new com.mccompanion.runtime.conversation.ConversationRepository(conversationDatabase)
                    .list("companion-1", 10);
            assertEquals(List.of("MESSAGE", "CHAT"), transcript.stream()
                    .map(com.mccompanion.runtime.conversation.ConversationEvent::kind).toList());
            assertEquals("USER", transcript.getFirst().direction());
            assertEquals("ASSISTANT", transcript.getLast().direction());
            assertTrue(transcript.getLast().gameDelivered());
            client.closeBlocking();
        }
        assertTrue(Files.isRegularFile(config.databasePath()));
        assertTrue(Files.isRegularFile(config.logPath()));
    }

    @Test
    void rejectsWrongPairingToken() throws Exception {
        RuntimeConfig config = RuntimeConfig.defaults(temporary.resolve("rejected"));
        config.server.port = 0;
        config.logging.console = false;
        try (RuntimeApplication application = RuntimeApplication.start(config, false)) {
            TestClient client = new TestClient(new URI("ws://127.0.0.1:" + application.port()));
            assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
            client.send("""
                    {"type":"hello","protocol":"mc-companion/1","token":"wrong-token-value",
                     "modVersion":"0.1.0-alpha","minecraftVersion":"1.21.1","loader":"fabric",
                     "worldId":"world-test","capabilities":{}}
                    """);
            JsonNode response = client.awaitType("hello_ack", 5);
            assertFalse(response.path("accepted").asBoolean());
            assertEquals("AUTH_FAILED", response.path("code").asText());
            client.closeBlocking();
        }
    }

    @Test
    void externalBrainEndpointRunsReplayToolLoopAndPersistsFinalReply() throws Exception {
        RuntimeConfig config = RuntimeConfig.defaults(temporary.resolve("external-brain"));
        config.server.port = 0;
        config.server.managementPort = freePort();
        config.logging.console = false;
        ReplayBrainAdapter replay = new ReplayBrainAdapter(request -> {
            if (request.toolResults().isEmpty()) {
                return BrainTurnResult.tools(List.of(new ToolCall("observe-1", "world.observe", Json.object())));
            }
            String lastTool = request.toolResults().getLast().toolName();
            if (lastTool.equals("world.observe")) {
                return BrainTurnResult.tools(List.of(new ToolCall("search-1", "search.query",
                        Json.object().put("query", "Fabric supported versions"))));
            }
            if (lastTool.equals("search.query")) {
                return BrainTurnResult.tools(List.of(new ToolCall("open-1", "search.open",
                        Json.object().put("sourceId", "fabric-docs"))));
            }
            return BrainTurnResult.finalResponse("Replay verified world state and cited documentation.");
        });
        SearchSource searchSource = new SearchSource("fabric-docs", "Fabric documentation",
                "https://docs.fabricmc.net/", "docs.fabricmc.net", "Fabric", null, Instant.now(),
                "Supported versions", "OFFICIAL", "text/html");
        ReplaySearchProvider search = new ReplaySearchProvider(List.of(searchSource), Map.of("fabric-docs",
                new SearchPage("fabric-docs", searchSource.title(), searchSource.url(), searchSource.domain(),
                        "UNTRUSTED_EXTERNAL_CONTENT\nVersion documentation", "text/html", false, Instant.now())));

        try (RuntimeApplication application = RuntimeApplication.start(config, false, replay, search)) {
            String token = Files.readString(config.tokenPath()).trim();
            TestClient client = new TestClient(new URI("ws://127.0.0.1:" + application.port()));
            assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
            client.send("""
                    {"type":"hello","protocol":"mc-companion/1","token":"%s",
                     "modVersion":"0.1.0-alpha","minecraftVersion":"1.21.1","loader":"fabric",
                     "worldId":"brain-world","capabilities":{"NavigateTo":true}}
                    """.formatted(token));
            String sessionId = client.awaitType("hello_ack", 5).path("sessionId").asText();
            client.send("""
                    {"type":"companion_status","sessionId":"%s","sequence":0,"payload":{
                      "companionId":"brain-companion","ownerId":"owner-1","displayName":"Brain Companion",
                      "worldId":"brain-world","dimension":"minecraft:overworld",
                      "position":{"x":3,"y":70,"z":5},"bodyState":"spawned",
                      "behaviorRevision":0,"controlEpoch":0,"runtimeConnected":true,
                      "capabilities":{},"observedAt":"%s"}}
                    """.formatted(sessionId, Instant.now()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (application.companions().get("brain-companion").isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }

            String requestBody = """
                    {"controllerId":"runtime-primary","companionId":"brain-companion",
                     "text":"What do you see?"}
                    """;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI("http://127.0.0.1:" + config.server.managementPort + "/brain"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode body = Json.parse(response.body());
            assertEquals("FINAL_RESPONSE", body.path("result").path("kind").asText());
            assertEquals("Replay verified world state and cited documentation.", body.path("result").path("response").asText());
            assertEquals("world.observe", body.path("result").path("toolResults").path(0).path("toolName").asText());
            assertTrue(body.path("result").path("toolResults").path(0).path("success").asBoolean());
            assertEquals("search.query", body.path("result").path("toolResults").path(1).path("toolName").asText());
            assertEquals("search.open", body.path("result").path("toolResults").path(2).path("toolName").asText());
            assertTrue(body.path("result").path("toolResults").path(2).path("observation")
                    .path("content").asText().startsWith("UNTRUSTED_EXTERNAL_CONTENT"));
            HttpResponse<String> audit = HttpClient.newHttpClient().send(HttpRequest.newBuilder(new URI(
                            "http://127.0.0.1:" + config.server.managementPort
                                    + "/brain/audit?companionId=brain-companion"))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, audit.statusCode(), audit.body());
            assertEquals(3, Json.parse(audit.body()).path(0).path("toolCalls").size());
            assertTrue(application.plans().activeForCompanion("brain-companion").isEmpty());
            assertTrue(application.commands().activeTaskFor("brain-companion").isEmpty());

            client.send("""
                    {"type":"player_request","sessionId":"%s","sequence":1,"payload":{
                      "requestId":"brain-game-1","companionId":"brain-companion","ownerId":"owner-1",
                      "text":"Check again from the game chat."}}
                    """.formatted(sessionId));
            JsonNode gameReply = client.awaitType("player_reply", 5);
            assertTrue(gameReply.path("payload").path("accepted").asBoolean());
            assertEquals("external-brain", gameReply.path("payload").path("source").asText());
            assertEquals("FINAL_RESPONSE", gameReply.path("payload").path("decision").asText());

            URI memoryUri = new URI("http://127.0.0.1:" + config.server.managementPort
                    + "/memories?companionId=brain-companion");
            HttpResponse<String> savedMemory = HttpClient.newHttpClient().send(HttpRequest.newBuilder(memoryUri)
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"kind":"PREFERENCE","key":"reply_style","value":"concise"}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, savedMemory.statusCode(), savedMemory.body());
            String memoryId = Json.parse(savedMemory.body()).path("memoryId").asText();
            HttpResponse<String> listedMemory = HttpClient.newHttpClient().send(HttpRequest.newBuilder(memoryUri)
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("USER", Json.parse(listedMemory.body()).path("byKind").path("PREFERENCE")
                    .path(0).path("source").asText());
            HttpResponse<String> deletedMemory = HttpClient.newHttpClient().send(HttpRequest.newBuilder(new URI(
                            memoryUri + "&memoryId=" + memoryId)).header("Authorization", "Bearer " + token)
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(Json.parse(deletedMemory.body()).path("deleted").asBoolean());

            var transcript = new com.mccompanion.runtime.conversation.ConversationRepository(
                    new com.mccompanion.runtime.db.RuntimeDatabase(config.databasePath())).list("brain-companion", 10);
            assertEquals(List.of("MESSAGE", "CHAT", "MESSAGE", "CHAT"), transcript.stream().map(
                    com.mccompanion.runtime.conversation.ConversationEvent::kind).toList());
            client.closeBlocking();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class TestClient extends WebSocketClient {
        private final List<JsonNode> messages = new CopyOnWriteArrayList<>();
        private final CountDownLatch messageArrived = new CountDownLatch(1);

        private TestClient(URI uri) {
            super(uri);
        }

        @Override public void onOpen(ServerHandshake handshake) { }
        @Override public void onMessage(String message) {
            messages.add(Json.parse(message));
            messageArrived.countDown();
        }
        @Override public void onClose(int code, String reason, boolean remote) { }
        @Override public void onError(Exception exception) { }

        private JsonNode awaitType(String type, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline) {
                for (JsonNode message : messages) {
                    if (type.equals(message.path("type").asText())) return message;
                }
                messageArrived.await(20, TimeUnit.MILLISECONDS);
            }
            fail("Timed out waiting for WebSocket message type " + type + "; received=" + messages);
            return Json.object();
        }
    }
}
