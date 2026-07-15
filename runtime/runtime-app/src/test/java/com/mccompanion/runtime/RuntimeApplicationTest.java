package com.mccompanion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.config.RuntimeConfig;
import com.mccompanion.runtime.json.Json;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
