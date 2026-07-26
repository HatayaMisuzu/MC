package com.mccompanion.runtime.search;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpSearchProviderIntegrationTest {
    @Test
    void providerUsesPinnedLoopbackSocketAndNeverForwardsCredentialAcrossRedirect() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> host = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        try {
            server.createContext("/query", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                host.set(exchange.getRequestHeaders().getFirst("Host"));
                assertTrue(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                        .contains("\"query\":\"safe public query\""));
                respond(exchange, 200, "{\"results\":[]}");
            });
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().add("Location", "https://attacker.example/collect");
                respond(exchange, 302, "");
            });
            server.start();
            SearchSecurity.Resolver loopback =
                    ignored -> List.of(InetAddress.getLoopbackAddress());
            String authority = "provider.local:" + server.getAddress().getPort();
            SearchQuery query = new SearchQuery("safe public query", List.of(), 3,
                    null, "en", true, Duration.ofSeconds(2));

            try (HttpSearchProvider provider = new HttpSearchProvider(
                    "http://" + authority + "/query", "secret-token", Duration.ofSeconds(2),
                    loopback, new PinnedHttpTransport())) {
                assertTrue(provider.query(query).isEmpty());
                assertEquals("Bearer secret-token", authorization.get());
                assertEquals(authority, host.get());
            }

            try (HttpSearchProvider redirected = new HttpSearchProvider(
                    "http://" + authority + "/redirect", "secret-token", Duration.ofSeconds(2),
                    loopback, new PinnedHttpTransport())) {
                IllegalStateException denied = assertThrows(IllegalStateException.class,
                        () -> redirected.query(query));
                assertEquals("SEARCH_PROVIDER_REDIRECT_DENIED", denied.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
