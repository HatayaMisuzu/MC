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
import com.mccompanion.runtime.workspace.SkillRepository;
import com.mccompanion.runtime.security.Digests;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeApplicationTest {
    @TempDir Path temporary;

    @Test
    void localManagementApprovesGeneratedSkillButBrainToolOnlyCreatesPendingReview() throws Exception {
        RuntimeConfig config = RuntimeConfig.defaults(temporary.resolve("skill-management"));
        config.server.port = 0;
        config.server.managementPort = freePort();
        config.logging.console = false;
        try (RuntimeApplication application = RuntimeApplication.start(config, false)) {
            String token = Files.readString(config.tokenPath()).trim();
            SkillRepository repository = new SkillRepository(
                    new com.mccompanion.runtime.db.RuntimeDatabase(config.databasePath()));
            String document = """
                    version: mcac-task-graph/1
                    id: managed_skill
                    permissions: []
                    root:
                      id: done
                      type: return
                      value: complete
                    """;
            var pending = repository.requestPromotion(config.server.profileId, "companion-1",
                    "managed_skill", "yaml", document, Digests.sha256(document),
                    Json.MAPPER.createArrayNode(), Json.object().put("provider", "replay-test"),
                    Json.object().put("valid", true), "controller", "brain-session");
            assertEquals("PENDING_REVIEW", pending.status());

            HttpClient http = HttpClient.newHttpClient();
            URI endpoint = new URI("http://127.0.0.1:" + config.server.managementPort + "/skills");
            HttpResponse<String> unauthorized = http.send(HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(Json.object()
                            .put("action", "approve").put("requestId", pending.requestId()))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            HttpResponse<String> approved = http.send(HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(Json.object()
                            .put("action", "approve").put("requestId", pending.requestId()))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, approved.statusCode(), approved.body());
            assertEquals("ACTIVE", Json.parse(approved.body()).path("status").asText());
            assertEquals("LOCAL_MANAGEMENT_USER", Json.parse(approved.body()).path("approvedBy").asText());

            HttpResponse<String> listed = http.send(HttpRequest.newBuilder(new URI(
                            endpoint + "?companionId=companion-1"))
                    .header("Authorization", "Bearer " + token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listed.statusCode(), listed.body());
            assertEquals("ACTIVE", Json.parse(listed.body()).path("versions").path(0).path("status").asText());
        }
    }

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
            assertEquals("AVAILABLE_NOW", playerReply.path("payload").path("capabilityStates")
                    .path("CraftItem").path("state").asText());
            assertTrue(application.plans().activeForCompanion("companion-1").isEmpty(),
                    "a conversational status response must not create an agent plan");
            assertTrue(application.commands().activeTaskFor("companion-1").isEmpty(),
                    "a conversational status response must not start a Minecraft task");
            var conversationDatabase = new com.mccompanion.runtime.db.RuntimeDatabase(config.databasePath());
            var conversationRepository = new com.mccompanion.runtime.conversation.ConversationRepository(conversationDatabase);
            var transcript = conversationRepository.list("companion-1", 10);
            assertEquals(List.of("MESSAGE", "CHAT"), transcript.stream()
                    .map(com.mccompanion.runtime.conversation.ConversationEvent::kind).toList());
            assertEquals("USER", transcript.getFirst().direction());
            assertEquals("ASSISTANT", transcript.getLast().direction());
            long deliveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!transcript.getLast().gameDelivered() && System.nanoTime() < deliveryDeadline) {
                Thread.sleep(10);
                transcript = conversationRepository.list("companion-1", 10);
            }
            assertTrue(transcript.getLast().gameDelivered(), "game delivery audit should follow the async WebSocket send");
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
    void exposesAuthenticatedSessionBoundMcpToolsOverStreamableHttp() throws Exception {
        RuntimeConfig config = RuntimeConfig.defaults(temporary.resolve("mcp"));
        config.server.port = 0;
        config.server.managementPort = freePort();
        config.logging.console = false;
        config.provider.mode = "rules";
        try (RuntimeApplication application = RuntimeApplication.start(config, false)) {
            String token = Files.readString(config.tokenPath()).trim();
            URI endpoint = new URI("http://127.0.0.1:" + config.server.managementPort + "/mcp");
            HttpClient http = HttpClient.newHttpClient();

            HttpResponse<String> unauthorized = http.send(HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode(), unauthorized.body());

            HttpResponse<String> initialized = http.send(HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("X-MCAC-Controller-Id", "hermes-test")
                    .header("X-MCAC-Brain-Session-Id", "brain-session-1")
                    .header("X-MCAC-Companion-Id", "missing-companion")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{
                              "protocolVersion":"2025-06-18","capabilities":{},
                              "clientInfo":{"name":"Hermes","version":"test"}}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, initialized.statusCode(), initialized.body());
            JsonNode initializeBody = Json.parse(initialized.body());
            assertEquals("init-1", initializeBody.path("id").asText());
            assertEquals("2025-06-18", initializeBody.path("result").path("protocolVersion").asText());
            assertTrue(initializeBody.path("result").path("capabilities").has("tools"));
            String mcpSession = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
            assertEquals(43, mcpSession.length());

            HttpResponse<String> unbound = http.send(HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", "2025-06-18")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(-32602, Json.parse(unbound.body()).path("error").path("code").asInt());

            HttpResponse<String> incompatible = http.send(HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", "2024-11-05")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"old-version","method":"tools/list","params":{}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, incompatible.statusCode(), incompatible.body());
            assertEquals(-32600, Json.parse(incompatible.body()).path("error").path("code").asInt());

            HttpRequest.Builder bound = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", "2025-06-18")
                    .header("X-MCAC-Controller-Id", "hermes-test")
                    .header("X-MCAC-Brain-Session-Id", "brain-session-1")
                    .header("X-MCAC-Companion-Id", "missing-companion")
                    .header("Mcp-Session-Id", mcpSession);
            HttpResponse<String> listed = http.send(bound.copy()
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listed.statusCode(), listed.body());
            List<String> names = new java.util.ArrayList<>();
            Json.parse(listed.body()).path("result").path("tools")
                    .forEach(tool -> names.add(tool.path("name").asText()));
            assertTrue(names.contains("world.observe"), names.toString());
            assertTrue(names.contains("memory.search"), names.toString());
            assertTrue(names.stream().noneMatch(name -> name.contains("shell") || name.contains("file")),
                    names.toString());

            HttpResponse<String> called = http.send(bound.copy()
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"observe-1","method":"tools/call","params":{
                              "name":"world.observe","arguments":{}}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode callResult = Json.parse(called.body()).path("result");
            assertTrue(callResult.path("isError").asBoolean(), called.body());
            assertEquals("INVALID_TOOL_ARGUMENTS",
                    callResult.path("structuredContent").path("code").asText());
            assertTrue(callResult.path("structuredContent").path("callId").asText().startsWith("mcp-"));

            HttpResponse<String> replayed = http.send(bound.copy()
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"observe-1","method":"tools/call","params":{
                              "name":"world.observe","arguments":{}}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(callResult, Json.parse(replayed.body()).path("result"), replayed.body());

            HttpResponse<String> conflictingReplay = http.send(bound.copy()
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"observe-1","method":"tools/call","params":{
                              "name":"world.observe","arguments":{"radius":1}}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("MCP_REQUEST_REPLAY_CONFLICT", Json.parse(conflictingReplay.body())
                    .path("result").path("structuredContent").path("code").asText(),
                    conflictingReplay.body());

            String secondSession = initializeMcpSession(http, endpoint, token,
                    "hermes-test", "brain-session-1", "missing-companion");
            HttpResponse<String> newSessionCall = http.send(bound.copy()
                    .setHeader("Mcp-Session-Id", secondSession)
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"observe-1","method":"tools/call","params":{
                              "name":"world.observe","arguments":{}}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertNotEquals(callResult.path("structuredContent").path("callId").asText(),
                    Json.parse(newSessionCall.body()).path("result").path("structuredContent")
                            .path("callId").asText(),
                    "JSON-RPC ids are unique only within their MCP transport session");

            HttpResponse<String> streamed = http.send(bound.copy().header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"observe-sse","method":"tools/call","params":{
                              "name":"world.observe","arguments":{},
                              "_meta":{"progressToken":"progress-observe"}}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, streamed.statusCode(), streamed.body());
            assertTrue(streamed.headers().firstValue("Content-Type").orElse("")
                    .startsWith("text/event-stream"));
            assertTrue(streamed.body().startsWith("event: message\ndata: "), streamed.body());
            JsonNode streamedResult = Json.parse(streamed.body().lines()
                    .filter(line -> line.startsWith("data: ")).findFirst().orElseThrow().substring(6));
            assertEquals("observe-sse", streamedResult.path("id").asText());
            assertTrue(streamedResult.path("result").path("isError").asBoolean());

            HttpResponse<String> cancelled = http.send(bound.copy()
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","method":"notifications/cancelled","params":{
                              "requestId":"observe-1","reason":"owner changed goal"}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, cancelled.statusCode(), cancelled.body());
            assertTrue(cancelled.body().isEmpty());

            HttpResponse<String> wrongScope = http.send(bound.copy()
                    .setHeader("X-MCAC-Companion-Id", "other-companion")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"wrong-scope","method":"ping","params":{}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, wrongScope.statusCode(), wrongScope.body());
            assertEquals("MCP_SESSION_NOT_FOUND", Json.parse(wrongScope.body()).path("code").asText());

            HttpResponse<String> deleted = http.send(bound.copy().DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(204, deleted.statusCode(), deleted.body());
            HttpResponse<String> afterDelete = http.send(bound.copy()
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"after-delete","method":"ping","params":{}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, afterDelete.statusCode(), afterDelete.body());
        }
    }

    @Test
    void routesLiveRegistryQueriesFromMcpThroughTheAuthenticatedModSession() throws Exception {
        RuntimeConfig config = RuntimeConfig.defaults(temporary.resolve("registry-mcp"));
        config.server.port = 0;
        config.server.managementPort = freePort();
        config.logging.console = false;
        config.provider.mode = "rules";
        try (RuntimeApplication application = RuntimeApplication.start(config, false)) {
            String token = Files.readString(config.tokenPath()).trim();
            TestClient client = new TestClient(new URI("ws://127.0.0.1:" + application.port()));
            assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
            client.send("""
                    {"type":"hello","protocol":"mc-companion/1","token":"%s",
                     "modVersion":"0.1.0-alpha","minecraftVersion":"1.21.1","loader":"fabric",
                     "worldId":"registry-world",
                     "capabilities":{"registry_query":true,"recipe_query":true,
                       "primitive_observation_query":true}}
                    """.formatted(token));
            String sessionId = client.awaitType("hello_ack", 5).path("sessionId").asText();
            client.send("""
                    {"type":"companion_status","sessionId":"%s","sequence":0,"payload":{
                      "companionId":"registry-companion","ownerId":"owner-1","displayName":"Registry Companion",
                      "worldId":"registry-world","dimension":"minecraft:overworld",
                      "position":{"x":0,"y":64,"z":0},"bodyState":"spawned",
                      "behaviorRevision":0,"controlEpoch":0,"runtimeConnected":true,
                      "capabilities":{},"observedAt":"%s"}}
                    """.formatted(sessionId, Instant.now()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (application.companions().get("registry-companion").isEmpty()
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(application.companions().get("registry-companion").isPresent());

            URI endpoint = new URI("http://127.0.0.1:" + config.server.managementPort + "/mcp");
            HttpClient http = HttpClient.newHttpClient();
            String mcpSession = initializeMcpSession(http, endpoint, token,
                    "hermes-test", "registry-brain-session", "registry-companion");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", "2025-06-18")
                    .header("X-MCAC-Controller-Id", "hermes-test")
                    .header("X-MCAC-Brain-Session-Id", "registry-brain-session")
                    .header("X-MCAC-Companion-Id", "registry-companion")
                    .header("Mcp-Session-Id", mcpSession)
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"registry-search","method":"tools/call","params":{
                              "name":"registry.search","arguments":{
                                "kind":"ITEM","namespace":"unknownmod","query":"blue","limit":4}}}
                            """)).build();
            CompletableFuture<HttpResponse<String>> response = CompletableFuture.supplyAsync(() -> {
                try {
                    return http.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });

            JsonNode command = client.awaitCommand("query_registry", 5);
            String queryId = command.path("payload").path("arguments").path("queryId").asText();
            assertFalse(queryId.isBlank());
            assertEquals("unknownmod",
                    command.path("payload").path("arguments").path("namespace").asText());
            client.send("""
                    {"type":"registry_result","sessionId":"%s","sequence":1,"payload":{
                      "queryId":"%s","companionId":"registry-companion","success":true,"code":"OK",
                      "observation":{"source":"LIVE_SERVER_REGISTRY","totalMatches":1,"truncated":false,
                        "entries":[{"kind":"ITEM","id":"unknownmod:blue_gem",
                          "namespace":"unknownmod","path":"blue_gem"}]}}}
                    """.formatted(sessionId, queryId));

            HttpResponse<String> completed = response.get(5, TimeUnit.SECONDS);
            assertEquals(200, completed.statusCode(), completed.body());
            JsonNode result = Json.parse(completed.body()).path("result");
            assertFalse(result.path("isError").asBoolean(), completed.body());
            assertEquals("unknownmod:blue_gem", result.path("structuredContent")
                    .path("observation").path("entries").path(0).path("id").asText());

            HttpRequest inspectRequest = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", "2025-06-18")
                    .header("X-MCAC-Controller-Id", "hermes-test")
                    .header("X-MCAC-Brain-Session-Id", "registry-brain-session")
                    .header("X-MCAC-Companion-Id", "registry-companion")
                    .header("Mcp-Session-Id", mcpSession)
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"block-inspect","method":"tools/call","params":{
                              "name":"block.inspect","arguments":{
                                "position":{"dimension":"unknownmod:moon","x":2,"y":70,"z":-3}}}}
                            """)).build();
            CompletableFuture<HttpResponse<String>> inspectResponse = CompletableFuture.supplyAsync(() -> {
                try {
                    return http.send(inspectRequest, HttpResponse.BodyHandlers.ofString());
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            JsonNode inspectCommand = client.awaitCommand("query_observation", 5);
            String inspectQueryId = inspectCommand.path("payload").path("arguments").path("queryId").asText();
            assertEquals("block.inspect",
                    inspectCommand.path("payload").path("arguments").path("tool").asText());
            client.send("""
                    {"type":"observation_result","sessionId":"%s","sequence":2,"payload":{
                      "queryId":"%s","companionId":"registry-companion","success":true,"code":"OK",
                      "observation":{"source":"LIVE_SERVER_OBSERVATION","kind":"BLOCK",
                        "block":"unknownmod:blue_block","visible":true}}}
                    """.formatted(sessionId, inspectQueryId));
            HttpResponse<String> inspected = inspectResponse.get(5, TimeUnit.SECONDS);
            assertEquals("unknownmod:blue_block", Json.parse(inspected.body()).path("result")
                    .path("structuredContent").path("observation").path("block").asText());
            client.closeBlocking();
        }
    }

    @Test
    void externalBrainEndpointRunsReplayToolLoopAndPersistsFinalReply() throws Exception {
        RuntimeConfig config = RuntimeConfig.defaults(temporary.resolve("external-brain"));
        config.server.port = 0;
        config.server.managementPort = freePort();
        config.logging.console = false;
        CountDownLatch blockedBrainEntered = new CountDownLatch(1);
        CountDownLatch releaseBlockedBrain = new CountDownLatch(1);
        AtomicReference<String> askedSession = new AtomicReference<>();
        ReplayBrainAdapter replay = new ReplayBrainAdapter(request -> {
            if (request.userMessage().equals("Bring 16 iron ingots")
                    || request.userMessage().equals("Game needs a choice")) {
                askedSession.set(request.sessionId());
                return BrainTurnResult.askUser(new com.mccompanion.runtime.brain.BrainQuestion(
                        "Only 6 ingots are available. What should I do?", "RESOURCE_SHORTAGE",
                        List.of(new com.mccompanion.runtime.conversation.ConversationOption(
                                        "deliver_partial", "Deliver 6", "Deliver available stock"),
                                new com.mccompanion.runtime.conversation.ConversationOption(
                                        "collect_missing", "Collect 10", "Collect the remainder")),
                        false, Json.object().put("available", 6).put("requested", 16), null));
            }
            if (request.userMessage().contains("\"type\":\"user_answer\"")) {
                assertEquals(askedSession.get(), request.sessionId());
                assertTrue(request.userMessage().contains("\"optionId\":\"deliver_partial\""));
                return BrainTurnResult.finalResponse("I will deliver the available 6 ingots.");
            }
            if (request.userMessage().equals("How are you?")) {
                return BrainTurnResult.finalResponse("I am ready; the earlier choice is still waiting.");
            }
            if (request.userMessage().equals("Hold this Brain request")) {
                blockedBrainEntered.countDown();
                try {
                    if (!releaseBlockedBrain.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test Brain request was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test Brain request was interrupted", interrupted);
                }
                return BrainTurnResult.finalResponse("Released.");
            }
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

            HttpResponse<String> asked = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                    .uri(new URI("http://127.0.0.1:" + config.server.managementPort + "/brain"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"controllerId":"runtime-primary","companionId":"brain-companion",
                             "text":"Bring 16 iron ingots"}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, asked.statusCode(), asked.body());
            JsonNode askedBody = Json.parse(asked.body()).path("result");
            assertEquals("ASK_USER", askedBody.path("kind").asText());
            String questionId = askedBody.path("question").path("questionId").asText();
            String brainSessionId = askedBody.path("sessionId").asText();
            assertFalse(questionId.isBlank());
            assertEquals(brainSessionId, askedBody.path("question").path("brainSessionId").asText());
            assertEquals("deliver_partial", askedBody.path("question").path("options").path(0).path("id").asText());

            HttpResponse<String> ordinaryChat = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                    .uri(new URI("http://127.0.0.1:" + config.server.managementPort + "/brain"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"controllerId":"runtime-primary","companionId":"brain-companion",
                             "text":"How are you?"}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ordinaryChat.statusCode(), ordinaryChat.body());
            assertEquals("FINAL_RESPONSE", Json.parse(ordinaryChat.body()).path("result").path("kind").asText());
            assertEquals(questionId, new com.mccompanion.runtime.conversation.ConversationRepository(
                    new com.mccompanion.runtime.db.RuntimeDatabase(config.databasePath()))
                    .activeForCompanion("brain-companion").orElseThrow().questionId());

            HttpResponse<String> answered = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                    .uri(new URI("http://127.0.0.1:" + config.server.managementPort + "/brain"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"controllerId":"runtime-primary","companionId":"brain-companion",
                             "text":"deliver_partial"}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, answered.statusCode(), answered.body());
            JsonNode answeredBody = Json.parse(answered.body()).path("result");
            assertEquals("FINAL_RESPONSE", answeredBody.path("kind").asText());
            assertEquals(brainSessionId, answeredBody.path("sessionId").asText());
            var conversationRepository = new com.mccompanion.runtime.conversation.ConversationRepository(
                    new com.mccompanion.runtime.db.RuntimeDatabase(config.databasePath()));
            assertTrue(conversationRepository.activeForCompanion("brain-companion").isEmpty());
            assertEquals(1, conversationRepository.list("brain-companion", 20).stream()
                    .filter(event -> event.kind().equals("ANSWER")
                            && questionId.equals(event.questionId())).count());

            HttpRequest blockedRequest = HttpRequest.newBuilder()
                    .uri(new URI("http://127.0.0.1:" + config.server.managementPort + "/brain"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"controllerId":"runtime-primary","companionId":"brain-companion",
                             "text":"Hold this Brain request"}
                            """))
                    .build();
            var blockedResponse = CompletableFuture.supplyAsync(() -> {
                try {
                    return HttpClient.newHttpClient().send(blockedRequest, HttpResponse.BodyHandlers.ofString());
                } catch (Exception failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            });
            assertTrue(blockedBrainEntered.await(2, TimeUnit.SECONDS));
            try {
                HttpResponse<String> healthWhileBrainBlocked = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(new URI("http://127.0.0.1:" + config.server.managementPort + "/health"))
                                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, healthWhileBrainBlocked.statusCode(), healthWhileBrainBlocked.body());
            } finally {
                releaseBlockedBrain.countDown();
            }
            assertEquals(200, blockedResponse.get(3, TimeUnit.SECONDS).statusCode());

            client.send("""
                    {"type":"player_request","sessionId":"%s","sequence":1,"payload":{
                      "requestId":"brain-game-1","companionId":"brain-companion","ownerId":"owner-1",
                      "text":"Check again from the game chat."}}
                    """.formatted(sessionId));
            JsonNode gameReply = client.awaitType("player_reply", 5);
            assertTrue(gameReply.path("payload").path("accepted").asBoolean());
            assertEquals("external-brain", gameReply.path("payload").path("source").asText());
            assertEquals("FINAL_RESPONSE", gameReply.path("payload").path("decision").asText());

            client.send("""
                    {"type":"player_request","sessionId":"%s","sequence":2,"payload":{
                      "requestId":"brain-game-question","companionId":"brain-companion","ownerId":"owner-1",
                      "text":"Game needs a choice"}}
                    """.formatted(sessionId));
            JsonNode gameQuestion = client.awaitPlayerReply("brain-game-question", 5);
            assertEquals("ASK_USER", gameQuestion.path("payload").path("decision").asText());
            String gameQuestionId = gameQuestion.path("payload").path("waitingQuestion").path("questionId").asText();
            assertFalse(gameQuestionId.isBlank());
            client.send("""
                    {"type":"player_request","sessionId":"%s","sequence":3,"payload":{
                      "requestId":"brain-game-answer","companionId":"brain-companion","ownerId":"owner-1",
                      "text":"deliver_partial"}}
                    """.formatted(sessionId));
            JsonNode gameAnswer = client.awaitPlayerReply("brain-game-answer", 5);
            assertEquals("FINAL_RESPONSE", gameAnswer.path("payload").path("decision").asText());
            assertEquals("I will deliver the available 6 ingots.", gameAnswer.path("payload").path("reply").asText());

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
                    new com.mccompanion.runtime.db.RuntimeDatabase(config.databasePath())).list("brain-companion", 20);
            assertEquals(List.of("MESSAGE", "CHAT", "MESSAGE", "QUESTION", "MESSAGE", "CHAT", "ANSWER", "CHAT",
                    "MESSAGE", "CHAT", "MESSAGE", "CHAT", "MESSAGE", "QUESTION", "ANSWER", "CHAT"),
                    transcript.stream().map(
                    com.mccompanion.runtime.conversation.ConversationEvent::kind).toList());
            assertTrue(transcript.stream().filter(event -> gameQuestionId.equals(event.questionId()))
                    .findFirst().orElseThrow().gameDelivered());
            client.closeBlocking();
        }
    }

    @Test
    void taskGraphAskUserUsesSharedHttpAndGameAnswerRoutingWithoutSwallowingChat() throws Exception {
        RuntimeConfig config = RuntimeConfig.defaults(temporary.resolve("task-graph-ask"));
        config.server.port = 0;
        config.server.managementPort = freePort();
        config.logging.console = false;
        config.provider.mode = "rules";
        try (RuntimeApplication application = RuntimeApplication.start(config, false)) {
            String token = Files.readString(config.tokenPath()).trim();
            TestClient client = new TestClient(new URI("ws://127.0.0.1:" + application.port()));
            assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
            client.send("""
                    {"type":"hello","protocol":"mc-companion/1","token":"%s",
                     "modVersion":"0.1.0-alpha","minecraftVersion":"1.21.1","loader":"fabric",
                     "worldId":"graph-world","capabilities":{}}
                    """.formatted(token));
            String sessionId = client.awaitType("hello_ack", 5).path("sessionId").asText();
            client.send("""
                    {"type":"companion_status","sessionId":"%s","sequence":0,"payload":{
                      "companionId":"graph-companion","ownerId":"owner-1","displayName":"Graph Companion",
                      "worldId":"graph-world","dimension":"minecraft:overworld",
                      "position":{"x":0,"y":64,"z":0},"bodyState":"spawned",
                      "behaviorRevision":0,"controlEpoch":0,"runtimeConnected":true,
                      "capabilities":{},"observedAt":"%s"}}
                    """.formatted(sessionId, Instant.now()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (application.companions().get("graph-companion").isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            HttpClient http = HttpClient.newHttpClient();
            URI mcp = new URI("http://127.0.0.1:" + config.server.managementPort + "/mcp");
            String mcpSession = initializeMcpSession(http, mcp, token,
                    "hermes-test", "brain-graph", "graph-companion");
            java.util.function.Function<String, HttpRequest.Builder> mcpRequest = ignored ->
                    HttpRequest.newBuilder(mcp).header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .header("MCP-Protocol-Version", "2025-06-18")
                            .header("X-MCAC-Controller-Id", "hermes-test")
                            .header("X-MCAC-Brain-Session-Id", "brain-graph")
                            .header("X-MCAC-Companion-Id", "graph-companion")
                            .header("Mcp-Session-Id", mcpSession);

            HttpResponse<String> started = http.send(mcpRequest.apply("start").POST(
                    HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"graph-game","method":"tools/call","params":{
                              "name":"task_graph.execute","arguments":{"graph":{
                                "version":"mcac-task-graph/1","id":"game-answer","permissions":[],
                                "root":{"id":"root","type":"sequence","nodes":[
                                  {"id":"ask","type":"ask_user","prompt":"Continue?","options":["Yes","No"]},
                                  {"id":"done","type":"return","value":"${outputs.ask.text}"}
                                ]}}}}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode startedResult = Json.parse(started.body()).path("result").path("structuredContent");
            assertFalse(startedResult.path("isError").asBoolean(false), started.body());
            assertEquals("WAITING", startedResult.path("observation").path("state").asText());
            String executionId = startedResult.path("observation").path("executionId").asText();
            var conversations = new com.mccompanion.runtime.conversation.ConversationRepository(
                    new com.mccompanion.runtime.db.RuntimeDatabase(config.databasePath()));
            String questionId = conversations.activeForTaskGraph(executionId).orElseThrow().questionId();

            HttpResponse<String> ordinary = http.send(HttpRequest.newBuilder(
                            new URI("http://127.0.0.1:" + config.server.managementPort + "/brain"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"controllerId":"runtime-primary","companionId":"graph-companion",
                             "text":"How are you?"}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, ordinary.statusCode(), ordinary.body());
            assertEquals(questionId, conversations.activeForTaskGraph(executionId).orElseThrow().questionId());
            assertEquals(0, conversations.list("graph-companion", 20).stream()
                    .filter(event -> event.kind().equals("ANSWER")).count());

            client.send("""
                    {"type":"player_request","sessionId":"%s","sequence":1,"payload":{
                      "requestId":"graph-game-answer","companionId":"graph-companion","ownerId":"owner-1",
                      "text":"Yes"}}
                    """.formatted(sessionId));
            JsonNode gameAnswer = client.awaitPlayerReply("graph-game-answer", 5);
            assertEquals("task-graph", gameAnswer.path("payload").path("source").asText());
            assertTrue(gameAnswer.path("payload").path("accepted").asBoolean());
            assertEquals("RESUME_ACCEPTED", gameAnswer.path("payload").path("code").asText());

            JsonNode inspectedGraph = awaitTaskGraphState(http, mcpRequest, executionId, "SUCCEEDED");
            assertEquals("SUCCEEDED", inspectedGraph.path("state").asText(), inspectedGraph.toString());
            assertEquals("Yes", inspectedGraph.path("value").asText());

            HttpResponse<String> httpStarted = http.send(mcpRequest.apply("start-http").POST(
                    HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"graph-http","method":"tools/call","params":{
                              "name":"task_graph.execute","arguments":{"graph":{
                                "version":"mcac-task-graph/1","id":"http-answer","permissions":[],
                                "root":{"id":"root","type":"sequence","nodes":[
                                  {"id":"ask","type":"ask_user","prompt":"Choose","options":["Yes","No"]},
                                  {"id":"done","type":"return","value":"${outputs.ask.text}"}
                                ]}}}}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            String httpExecution = Json.parse(httpStarted.body()).path("result").path("structuredContent")
                    .path("observation").path("executionId").asText();
            assertFalse(httpExecution.isBlank(), httpStarted.body());
            HttpResponse<String> httpAnswer = http.send(HttpRequest.newBuilder(
                            new URI("http://127.0.0.1:" + config.server.managementPort + "/brain"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"controllerId":"runtime-primary","companionId":"graph-companion","text":"No"}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, httpAnswer.statusCode(), httpAnswer.body());
            assertEquals("task-graph", Json.parse(httpAnswer.body()).path("source").asText());
            assertEquals("RESUME_ACCEPTED", Json.parse(httpAnswer.body()).path("code").asText());
            JsonNode httpGraph = awaitTaskGraphState(http, mcpRequest, httpExecution, "SUCCEEDED");
            assertEquals("No", httpGraph.path("value").asText());
            client.closeBlocking();
        }
    }

    private static JsonNode awaitTaskGraphState(
            HttpClient http,
            java.util.function.Function<String, HttpRequest.Builder> mcpRequest,
            String executionId,
            String expectedState) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        JsonNode latest = Json.object();
        String latestBody = "";
        int poll = 0;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> inspected = http.send(mcpRequest.apply("inspect").POST(
                    HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":"inspect-%s-%d","method":"tools/call","params":{
                              "name":"task_graph.inspect","arguments":{"executionId":"%s"}}}
                            """.formatted(executionId, poll++, executionId))).build(),
                    HttpResponse.BodyHandlers.ofString());
            latestBody = inspected.body();
            latest = Json.parse(latestBody).path("result").path("structuredContent").path("observation");
            if (expectedState.equals(latest.path("state").asText())) return latest;
            Thread.sleep(20);
        }
        fail("Task Graph did not reach " + expectedState + "; latest=" + latestBody);
        return latest;
    }

    private static String initializeMcpSession(
            HttpClient http,
            URI endpoint,
            String token,
            String controllerId,
            String brainSessionId,
            String companionId) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("X-MCAC-Controller-Id", controllerId)
                        .header("X-MCAC-Brain-Session-Id", brainSessionId)
                        .header("X-MCAC-Companion-Id", companionId)
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"jsonrpc":"2.0","id":"session-init","method":"initialize","params":{
                                  "protocolVersion":"2025-06-18","capabilities":{},
                                  "clientInfo":{"name":"mcac-test","version":"test"}}}
                                """)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("2025-06-18",
                Json.parse(response.body()).path("result").path("protocolVersion").asText());
        return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
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

        private JsonNode awaitPlayerReply(String requestId, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline) {
                for (JsonNode message : messages) {
                    if ("player_reply".equals(message.path("type").asText())
                            && requestId.equals(message.path("payload").path("requestId").asText())) return message;
                }
                messageArrived.await(20, TimeUnit.MILLISECONDS);
            }
            fail("Timed out waiting for player_reply " + requestId + "; received=" + messages);
            return Json.object();
        }

        private JsonNode awaitCommand(String command, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline) {
                for (JsonNode message : messages) {
                    if ("command".equals(message.path("type").asText())
                            && command.equals(message.path("payload").path("command").asText())) {
                        return message;
                    }
                }
                messageArrived.await(20, TimeUnit.MILLISECONDS);
            }
            fail("Timed out waiting for command " + command + "; received=" + messages);
            return Json.object();
        }
    }
}
