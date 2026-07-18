package com.mccompanion.terminal;

import com.mccompanion.terminal.runtime.RuntimeProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpProtocolDoctorTest {
    @TempDir Path temporary;

    @Test
    void negotiatesVersionListsBoundedToolsAndNeverExposesToken() throws Exception {
        Files.writeString(temporary.resolve("pairing.token"), "doctor-secret\n", StandardCharsets.US_ASCII);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/mcp", this::mcp);
        server.start();
        try {
            RuntimeProfile profile = new RuntimeProfile("test", temporary, temporary.resolve("start.cmd"),
                    8766, server.getAddress().getPort());
            McpProtocolDoctor.Result result = new McpProtocolDoctor().probe(profile);

            assertTrue(result.healthy(), result.detail());
            assertEquals("2025-06-18", result.protocolVersion());
            assertEquals(1, result.toolCount());
            assertTrue(!result.detail().contains("doctor-secret"));
            assertTrue(McpProtocolDoctor.isForbiddenToolName("shell.execute"));
            assertTrue(McpProtocolDoctor.isForbiddenToolName("filesystem.read"));
            assertTrue(McpProtocolDoctor.isForbiddenToolName("network.fetch"));
        } finally {
            server.stop(0);
        }
    }

    private void mcp(HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            assertEquals("Bearer doctor-secret", exchange.getRequestHeaders().getFirst("Authorization"));
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response;
            if (request.contains("\"initialize\"")) {
                assertEquals("mcac-doctor", exchange.getRequestHeaders().getFirst("X-MCAC-Controller-Id"));
                assertEquals("doctor-session", exchange.getRequestHeaders().getFirst("X-MCAC-Brain-Session-Id"));
                exchange.getResponseHeaders().set("Mcp-Session-Id", "doctor-opaque-session");
                response = """
                        {"jsonrpc":"2.0","id":"doctor-init","result":{"protocolVersion":"2025-06-18",
                         "capabilities":{"tools":{}},"serverInfo":{"name":"test","version":"1"}}}
                        """;
            } else {
                assertEquals("2025-06-18", exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                assertEquals("doctor-session", exchange.getRequestHeaders().getFirst("X-MCAC-Brain-Session-Id"));
                assertEquals("doctor-opaque-session", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                response = """
                        {"jsonrpc":"2.0","id":"doctor-list","result":{"tools":[
                         {"name":"world.observe","description":"Observe","inputSchema":{"type":"object"}}]}}
                        """;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
