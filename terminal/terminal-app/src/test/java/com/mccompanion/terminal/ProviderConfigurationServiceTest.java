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

class ProviderConfigurationServiceTest {
    @TempDir Path temp;

    @Test void rejectsUnsafeUrlAndInvalidEnvironmentName() {
        var service = new ProviderConfigurationService();
        RuntimeProfile profile = new RuntimeProfile("x", temp, temp.resolve("runtime.exe"), 8766);
        assertThrows(java.io.IOException.class,
                () -> service.configure(profile, "http://example.com/v1", "model", "KEY"));
        assertThrows(java.io.IOException.class,
                () -> service.configure(profile, "https://example.com/v1", "model", "bad-name"));
    }

    @Test void classifiesNonJsonAndRateLimitWithoutLeakingResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = "secret upstream text".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var node = JsonNodeFactory.instance.objectNode().put("baseUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .put("model", "test-model").put("timeoutSeconds", 2);
            var result = new ProviderConfigurationService().testWithKey(node, "test-secret-key");
            assertFalse(result.success());
            assertTrue(result.message().contains("429"));
            assertFalse(result.message().contains("secret"));
        } finally { server.stop(0); }
    }
}
