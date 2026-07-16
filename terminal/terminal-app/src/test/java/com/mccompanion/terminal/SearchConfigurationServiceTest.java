package com.mccompanion.terminal;

import com.mccompanion.terminal.runtime.RuntimeProfile;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchConfigurationServiceTest {
    @TempDir Path temporary;

    @Test
    void persistsPolicyWithoutCredentialAndRejectsUnsafeConfiguration() throws Exception {
        RuntimeProfile profile = new RuntimeProfile("search", temporary, temporary.resolve("start.cmd"), 8766);
        SearchConfigurationService service = new SearchConfigurationService();

        service.configure(profile, "https://search.example/v1/query", "MCAC_SEARCH_TOKEN", 12,
                List.of("Docs.Example", "wiki.example"), List.of("blocked.example"));

        var status = service.status(profile);
        assertEquals("http", status.path("mode").asText());
        assertEquals("docs.example", status.path("allowedDomains").path(0).asText());
        assertEquals("MCAC_SEARCH_TOKEN", status.path("tokenEnv").asText());
        assertTrue(!status.toString().contains("Bearer"));
        assertThrows(java.io.IOException.class, () -> service.configure(profile,
                "http://public.example/query", "MCAC_SEARCH_TOKEN", 12, List.of(), List.of()));
        assertThrows(java.io.IOException.class, () -> service.configure(profile,
                "https://search.example/query?api_key=must-not-be-stored", "MCAC_SEARCH_TOKEN", 12,
                List.of(), List.of()));
        assertThrows(java.io.IOException.class, () -> service.configure(profile,
                "https://search.example/query", "MCAC_SEARCH_TOKEN", 12,
                List.of("same.example"), List.of("same.example")));

        Files.writeString(service.file(profile), """
                {"mode":"http","endpoint":"https://search.example/query","tokenEnv":"MCAC_SEARCH_TOKEN",
                 "token":"must-not-leak","allowedDomains":[],"deniedDomains":[]}
                """);
        assertTrue(!service.status(profile).has("token"));
        assertThrows(java.io.IOException.class, () -> service.inspect(profile));

        service.disable(profile);
        var disabledDoctor = service.test(profile);
        assertTrue(disabledDoctor.success());
        assertTrue(!disabledDoctor.networkAttempted());
    }

    @Test
    void doctorUsesBoundedQueryAndDoesNotExposeProviderBodyOrToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
        var requestBody = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/search", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var configuration = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("mode", "http")
                    .put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/search")
                    .put("tokenEnv", "MCAC_SEARCH_TOKEN").put("timeoutSeconds", 2);
            configuration.putArray("allowedDomains");
            var result = new SearchConfigurationService().testWithToken(configuration, "doctor-secret");
            assertTrue(result.success());
            assertTrue(result.networkAttempted());
            assertEquals("Bearer doctor-secret", authorization.get());
            assertTrue(requestBody.get().contains("MCAC Search Doctor connectivity probe"));
            assertTrue(requestBody.get().contains("\"maxResults\":1"));
            assertTrue(!result.message().contains("doctor-secret"));
        } finally { server.stop(0); }
    }
}
