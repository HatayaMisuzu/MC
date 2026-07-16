package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExternalBrainAdapterTest {
    @Test
    void openAiCompatibleAdapterPerformsNativeToolCallingRoundTrip() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger turns = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (turns.getAndIncrement() == 0) {
                respond(exchange, 200, """
                        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                        {"id":"observe_1","type":"function","function":{"name":"world.observe","arguments":"{}"}}]}}]}
                        """);
            } else {
                respond(exchange, 200, """
                        {"choices":[{"message":{"role":"assistant","content":"生命值是 18，建议先休息。"}}]}
                        """);
            }
        }); OpenAiCompatibleBrainAdapter adapter = new OpenAiCompatibleBrainAdapter(
                server.baseUrl(), "fixture-token", "replay-model", Duration.ofSeconds(5), 512)) {
            BrainSession session = adapter.openSession(sessionRequest());
            BrainTurnResult first = adapter.continueTurn(new BrainTurnRequest(session.sessionId(),
                    "现在适合做什么？", context(), List.of(), 4));
            assertEquals(BrainTurnResult.Kind.TOOL_CALLS, first.kind());
            assertEquals("world.observe", first.toolCalls().getFirst().name());
            BrainTurnResult second = adapter.continueTurn(new BrainTurnRequest(session.sessionId(), "",
                    context(), List.of(new ToolResult("observe_1", "world.observe", true, "OK",
                    Json.object().put("health", 18), true)), 3));
            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, second.kind());
            assertTrue(second.response().contains("18"));
        }
        assertEquals(2, requests.size());
        JsonNodeView firstRequest = new JsonNodeView(requests.getFirst());
        assertEquals("world.observe", firstRequest.json.path("tools").path(0).path("function").path("name").asText());
        JsonNodeView secondRequest = new JsonNodeView(requests.getLast());
        assertTrue(secondRequest.json.path("messages").toString().contains("tool_call_id"));
    }

    @Test
    void hermesAdapterAndCoordinatorUseBoundedBridgeProtocol() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        AtomicInteger turns = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/sessions")) {
                respond(exchange, 200, "{\"sessionId\":\"hermes_session_1\"}");
            } else if (path.endsWith("/turns") && turns.getAndIncrement() == 0) {
                respond(exchange, 200, """
                        {"kind":"TOOL_CALLS","toolCalls":[{"callId":"observe_1",
                        "name":"world.observe","arguments":{}}]}
                        """);
            } else if (path.endsWith("/turns")) {
                respond(exchange, 200, "{\"kind\":\"FINAL_RESPONSE\",\"response\":\"状态正常。\"}");
            } else {
                respond(exchange, 200, "{}");
            }
        }); HermesBrainAdapter adapter = new HermesBrainAdapter(server.baseUrl(), "fixture-token", Duration.ofSeconds(5));
             ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(adapter, new ObserveGateway(), 4)) {
            BrainCoordinatorResult result = coordinator.continueTurn("hermes-controller", "c1", "看看状态", context());
            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
            assertEquals(1, result.toolResults().size());
        }
        assertEquals(List.of("/sessions", "/sessions/hermes_session_1/turns",
                "/sessions/hermes_session_1/turns", "/sessions/hermes_session_1/cancel"), paths);
    }

    @Test
    void openAiCompatibleAdapterReturnsStructuredAskUserWithoutExecutingItAsATool() throws Exception {
        try (TestServer server = new TestServer(exchange -> {
            var arguments = Json.object().put("prompt", "Only 6 ingots exist. What next?")
                    .put("reason", "RESOURCE_SHORTAGE").put("freeTextAllowed", false);
            var options = arguments.putArray("options");
            options.addObject().put("id", "deliver_partial").put("label", "Deliver 6");
            options.addObject().put("id", "collect_missing").put("label", "Collect 10");
            arguments.set("context", Json.object().put("available", 6));
            var response = Json.object();
            var function = response.putArray("choices").addObject().putObject("message")
                    .put("role", "assistant").putNull("content").putArray("tool_calls").addObject()
                    .put("id", "question_1").put("type", "function").putObject("function");
            function.put("name", "ask_user").put("arguments", Json.write(arguments));
            respond(exchange, 200, Json.write(response));
        }); OpenAiCompatibleBrainAdapter adapter = new OpenAiCompatibleBrainAdapter(
                server.baseUrl(), "fixture-token", "replay-model", Duration.ofSeconds(5), 512)) {
            BrainSession session = adapter.openSession(sessionRequest());
            BrainTurnResult result = adapter.continueTurn(new BrainTurnRequest(session.sessionId(),
                    "Bring 16 ingots", context(), List.of(), 4));
            assertEquals(BrainTurnResult.Kind.ASK_USER, result.kind());
            assertEquals("RESOURCE_SHORTAGE", result.question().reason());
            assertEquals(List.of("deliver_partial", "collect_missing"), result.question().options().stream()
                    .map(com.mccompanion.runtime.conversation.ConversationOption::id).toList());
        }
    }

    @Test
    void hermesAdapterValidatesStructuredAskUser() throws Exception {
        try (TestServer server = new TestServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/sessions")) {
                respond(exchange, 200, "{\"sessionId\":\"hermes_question_1\"}");
            } else {
                respond(exchange, 200, """
                        {"kind":"ASK_USER","question":{"prompt":"Deliver 6 or collect 10?",
                        "reason":"RESOURCE_SHORTAGE","options":[
                        {"id":"deliver_partial","label":"Deliver 6"},
                        {"id":"collect_missing","label":"Collect 10"}],
                        "freeTextAllowed":false,"context":{"available":6}}}
                        """);
            }
        }); HermesBrainAdapter adapter = new HermesBrainAdapter(
                server.baseUrl(), "fixture-token", Duration.ofSeconds(5))) {
            BrainSession session = adapter.openSession(sessionRequest());
            BrainTurnResult result = adapter.continueTurn(new BrainTurnRequest(session.sessionId(),
                    "Bring 16", context(), List.of(), 4));
            assertEquals(BrainTurnResult.Kind.ASK_USER, result.kind());
            assertEquals(2, result.question().options().size());
        }
    }

    @Test
    void adaptersRejectClearTextNonLoopbackEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> new OpenAiCompatibleBrainAdapter(
                "http://192.0.2.1", "fixture-token", "model", Duration.ofSeconds(1), 128));
        assertThrows(IllegalArgumentException.class, () -> new HermesBrainAdapter(
                "http://192.0.2.1", "fixture-token", Duration.ofSeconds(1)));
    }

    private static BrainSessionRequest sessionRequest() {
        return new BrainSessionRequest("controller", "c1", context(), new ObserveGateway().definitions(
                new ToolContext("controller", "opening", "c1")));
    }

    private static AgentContext context() {
        return AgentContext.empty("c1", List.of("FollowOwner"));
    }

    private static final class ObserveGateway implements ToolGateway {
        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("world.observe", "1.0", "Observe verified world state",
                    Json.object().put("type", "object").put("additionalProperties", false),
                    "LOW", "READ_WORLD", Duration.ofSeconds(5), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            return new ToolResult(call.callId(), call.name(), true, "OK", Json.object().put("health", 18), true);
        }
    }

    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws IOException; }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private TestServer(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try { handler.handle(exchange); }
                catch (Exception failure) { respond(exchange, 500, "{\"error\":\"fixture failed\"}"); }
            });
            server.start();
        }
        private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class JsonNodeView {
        private final com.fasterxml.jackson.databind.JsonNode json;
        private JsonNodeView(String value) { json = Json.parse(value); }
    }
}
