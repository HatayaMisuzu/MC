package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.terminal.runtime.RuntimeProfile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;

/** Live local-only MCP compatibility and exposed-permission probe. */
final class McpProtocolDoctor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERSION = "2025-06-18";
    private static final Set<String> FORBIDDEN_NAMES = Set.of(
            "shell", "world.edit", "inventory.edit");

    Result probe(RuntimeProfile profile) {
        try {
            var tokenFile = profile.profileDirectory().resolve("pairing.token");
            if (!Files.isRegularFile(tokenFile)) return Result.unavailable("Pairing token is unavailable");
            String token = Files.readString(tokenFile, StandardCharsets.US_ASCII).trim();
            if (token.isBlank()) return Result.unavailable("Pairing token is empty");
            URI endpoint = URI.create("http://127.0.0.1:" + profile.healthPort() + "/mcp");
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            ObjectNode initialize = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", "doctor-init")
                    .put("method", "initialize");
            initialize.set("params", JSON.createObjectNode().put("protocolVersion", VERSION)
                    .set("clientInfo", JSON.createObjectNode().put("name", "mcac-doctor").put("version", "0.3.0")));
            JsonNode initialized = post(http, endpoint, token, initialize, false);
            String version = initialized.path("result").path("protocolVersion").asText("");
            if (!VERSION.equals(version) || !initialized.path("result").path("capabilities").has("tools")) {
                return new Result(false, version, 0, "MCP initialize negotiation is incompatible");
            }

            ObjectNode list = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", "doctor-list")
                    .put("method", "tools/list");
            list.set("params", JSON.createObjectNode());
            JsonNode listed = post(http, endpoint, token, list, true);
            JsonNode tools = listed.path("result").path("tools");
            if (!tools.isArray()) return new Result(false, version, 0, "MCP tools/list returned no tool array");
            boolean observe = false;
            for (JsonNode tool : tools) {
                String name = tool.path("name").asText("");
                if (isForbiddenToolName(name)) {
                    return new Result(false, version, tools.size(), "MCP exposed forbidden tool: " + name);
                }
                if (name.equals("world.observe")) observe = true;
                if (!tool.path("inputSchema").isObject()) {
                    return new Result(false, version, tools.size(), "MCP tool schema is invalid: " + name);
                }
            }
            if (!observe) return new Result(false, version, tools.size(), "MCP world.observe is unavailable");
            return new Result(true, version, tools.size(), "MCP initialize and bounded tools/list passed");
        } catch (Exception unavailable) {
            return Result.unavailable("MCP probe failed: " + unavailable.getClass().getSimpleName());
        }
    }

    static boolean isForbiddenToolName(String name) {
        return FORBIDDEN_NAMES.contains(name) || name.startsWith("shell.") || name.startsWith("file.")
                || name.startsWith("filesystem.") || name.startsWith("http.") || name.startsWith("network.");
    }

    private static JsonNode post(HttpClient http, URI endpoint, String token, JsonNode body,
                                 boolean bound) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (bound) request.header("MCP-Protocol-Version", VERSION)
                .header("X-MCAC-Controller-Id", "mcac-doctor")
                .header("X-MCAC-Brain-Session-Id", "doctor-session")
                .header("X-MCAC-Companion-Id", "doctor-companion");
        HttpResponse<String> response = http.send(request.POST(
                HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        JsonNode parsed = JSON.readTree(response.body());
        if (parsed.has("error")) throw new IllegalStateException(parsed.path("error").path("message").asText());
        return parsed;
    }

    record Result(boolean healthy, String protocolVersion, int toolCount, String detail) {
        private static Result unavailable(String detail) { return new Result(false, "unavailable", 0, detail); }
    }
}
