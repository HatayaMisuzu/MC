package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BrainConfigurationServiceTest {
    @TempDir Path temporary;

    @Test
    void configurationIsIndependentAndNeverStoresTokenValue() throws Exception {
        RuntimeProfile profile = new RuntimeProfile("p", temporary, temporary.resolve("runtime.exe"), 8766);
        BrainConfigurationService service = new BrainConfigurationService();
        service.configure(profile, "hermes", "http://127.0.0.1:8080", "HERMES_TOKEN", "",
                90, 10, 1536, 40, 50_000, 10_000, 20, 3);

        String stored = java.nio.file.Files.readString(service.file(profile));
        assertTrue(stored.contains("\"mode\" : \"hermes\""));
        assertTrue(stored.contains("\"tokenEnv\" : \"HERMES_TOKEN\""));
        assertFalse(stored.contains("Bearer "));
        assertTrue(stored.contains("\"maxRequests\" : 40"));
        assertEquals("hermes", service.status(profile).path("mode").asText());
    }

    @Test
    void openAiProbeRequiresAnActualToolCall() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var returnTool = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/v1/chat/completions", exchange -> {
            String response = returnTool.get()
                    ? """
                      {"choices":[{"message":{"tool_calls":[{"id":"probe-1","type":"function",
                      "function":{"name":"mcac_health_probe","arguments":"{}"}}]}}]}
                      """
                    : "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var config = JsonNodeFactory.instance.objectNode()
                    .put("mode", "openai-compatible")
                    .put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .put("model", "fixture").put("timeoutSeconds", 2);
            BrainConfigurationService service = new BrainConfigurationService();
            var plainText = service.testWithToken(config, "fixture-token");
            assertFalse(plainText.success());
            assertEquals("PROTOCOL_INCOMPATIBLE", plainText.status());

            returnTool.set(true);
            var verified = service.testWithToken(config, "fixture-token");
            assertTrue(verified.success());
            assertEquals("TOOL_CALL_VERIFIED", verified.status());
        } finally {
            server.stop(0);
        }
    }
}
