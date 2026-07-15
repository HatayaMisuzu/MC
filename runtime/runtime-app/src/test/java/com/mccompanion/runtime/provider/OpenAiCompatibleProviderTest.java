package com.mccompanion.runtime.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.agent.DecisionKind;
import com.mccompanion.runtime.input.HintExtractor;
import com.mccompanion.runtime.input.TextNormalizer;
import com.mccompanion.runtime.intent.RuleIntentParser;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.Redactor;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleProviderTest {
    @TempDir Path temporary;

    @Test
    void callsDeepSeekCompatibleEndpointAndValidatesHighLevelJson() throws Exception {
        ObjectNode intent = Json.object().put("intent", "TRAVEL").put("x", 8).put("y", 70).put("z", -4);
        ObjectNode response = Json.object();
        response.putArray("choices").addObject().putObject("message").put("content", Json.write(intent));
        try (HttpStub stub = new HttpStub(200, Json.write(response));
             OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                     stub.baseUrl(), "test-secret-value", "deepseek-v4-flash", Duration.ofSeconds(5))) {
            Intent parsed = provider.parse("去目标位置");
            assertEquals(TaskType.TRAVEL, parsed.type());
            assertEquals(8, parsed.arguments().path("target").path("x").asInt());
            HttpRequestCapture capture = stub.capture().get(5, TimeUnit.SECONDS);
            assertEquals("/chat/completions", capture.path());
            assertEquals("Bearer test-secret-value", capture.authorization());
            JsonNodeAssertions.assertDeepSeekRequest(capture.body());
        }
    }

    @Test
    void rejectsUnknownIntentFieldsAndNeverExecutesThem() throws Exception {
        ObjectNode intent = Json.object().put("intent", "FOLLOW").put("setBlock", "stone");
        ObjectNode response = Json.object();
        response.putArray("choices").addObject().putObject("message").put("content", Json.write(intent));
        try (HttpStub stub = new HttpStub(200, Json.write(response));
             OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                     stub.baseUrl(), "test-secret-value", "deepseek-v4-flash", Duration.ofSeconds(5))) {
            ProviderException failure = assertThrows(ProviderException.class, () -> provider.parse("do something"));
            assertEquals("PROVIDER_INVALID_OUTPUT", failure.code());
        }
    }

    @Test
    void chatDecisionReceivesBoundedConversationAndPreferenceContext() throws Exception {
        ObjectNode decision = Json.object().put("kind", "RESPOND").put("understoodGoal", "轻松聊天")
                .put("reply", "那我们轻松聊会儿。").put("reason", "owner wants companionship");
        decision.putArray("constraints");
        decision.putArray("assumptions");
        decision.putArray("steps");
        ObjectNode response = Json.object();
        response.putArray("choices").addObject().putObject("message").put("content", Json.write(decision));
        try (HttpStub stub = new HttpStub(200, Json.write(response));
             OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                     stub.baseUrl(), "test-secret-value", "deepseek-v4-flash", Duration.ofSeconds(5))) {
            var preferences = Json.MAPPER.createArrayNode();
            preferences.addObject().put("key", "risk").set("value", Json.object().put("level", "LOW"));
            AgentContext context = new AgentContext("c1", Json.object().put("timeOfDay", "night"),
                    List.of("USER: I am tired today", "ASSISTANT: We can take it easy"), Json.object(),
                    List.of("基地"), List.of("FollowOwner"), preferences, 5);
            var input = new TextNormalizer().normalize("chat with me for a while");

            var result = provider.decide(new AgentRequest(input, new HintExtractor().extract(input), context));

            assertEquals(DecisionKind.RESPOND, result.kind());
            HttpRequestCapture capture = stub.capture().get(5, TimeUnit.SECONDS);
            var request = Json.parse(capture.body());
            String suppliedText = request.path("messages").path(1).path("content").asText();
            var supplied = Json.parse(suppliedText);
            assertEquals("USER: I am tired today", supplied.path("recentConversation").path(0).asText());
            assertEquals("LOW", supplied.path("preferences").path(0).path("value").path("level").asText());
            assertTrue(request.path("messages").path(0).path("content").asText()
                    .contains("Ordinary conversation"));
        }
    }

    @Test
    void routerFallsBackToRulesWithoutLeakingProviderFailure() throws Exception {
        try (HttpStub stub = new HttpStub(500, "failure");
             OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                     stub.baseUrl(), "test-secret-value", "deepseek-v4-flash", Duration.ofSeconds(5));
             RuntimeLog log = new RuntimeLog(temporary.resolve("runtime.log"), false, new Redactor())) {
            ProviderRouter router = new ProviderRouter(new RuleIntentParser(), provider, log);
            ProviderRouter.Resolution rules = router.resolve("跟着我");
            assertEquals("rules", rules.source());
            assertEquals(TaskType.FOLLOW, rules.intent().orElseThrow().type());
            ProviderRouter.Resolution failed = router.resolve("无法识别的自由文本");
            assertTrue(failed.fallbackUsed());
            assertTrue(failed.intent().isEmpty());
            assertEquals("PROVIDER_ERROR", failed.errorCode());
        }
    }

    private record HttpRequestCapture(String path, String authorization, String body) { }

    private static final class JsonNodeAssertions {
        private static void assertDeepSeekRequest(String body) {
            var json = Json.parse(body);
            assertEquals("deepseek-v4-flash", json.path("model").asText());
            assertEquals("json_object", json.path("response_format").path("type").asText());
            assertTrue(json.path("messages").path(0).path("content").asText().toLowerCase(Locale.ROOT)
                    .contains("json"));
        }
    }

    private static final class HttpStub implements AutoCloseable {
        private final ServerSocket server;
        private final Thread worker;
        private final CompletableFuture<HttpRequestCapture> capture = new CompletableFuture<>();
        private final int status;
        private final byte[] response;

        private HttpStub(int status, String response) throws IOException {
            this.server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
            this.status = status;
            this.response = response.getBytes(StandardCharsets.UTF_8);
            this.worker = new Thread(this::serve, "provider-http-stub");
            worker.setDaemon(true);
            worker.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        private CompletableFuture<HttpRequestCapture> capture() {
            return capture;
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                        StandardCharsets.US_ASCII));
                String requestLine = reader.readLine();
                int length = 0;
                String authorization = null;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String name = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if (name.equalsIgnoreCase("Content-Length")) length = Integer.parseInt(value);
                    if (name.equalsIgnoreCase("Authorization")) authorization = value;
                }
                char[] body = new char[length];
                int offset = 0;
                while (offset < length) {
                    int read = reader.read(body, offset, length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                String path = requestLine.split(" ")[1];
                capture.complete(new HttpRequestCapture(path, authorization, new String(body, 0, offset)));
                try (OutputStream output = socket.getOutputStream()) {
                    String header = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\n"
                            + "Content-Type: application/json\r\nContent-Length: " + response.length
                            + "\r\nConnection: close\r\n\r\n";
                    output.write(header.getBytes(StandardCharsets.US_ASCII));
                    output.write(response);
                    output.flush();
                }
            } catch (Exception failure) {
                capture.completeExceptionally(failure);
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            worker.join(2_000);
        }
    }
}
