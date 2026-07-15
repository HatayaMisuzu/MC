package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.terminal.runtime.RuntimeProfile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

/** Authenticated loopback client for Runtime command and task management. */
final class RuntimeControlClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    JsonNode execute(RuntimeProfile profile, String commandId, String companionId, String type,
                     ObjectNode arguments, Duration timeout) throws IOException {
        ObjectNode body = JSON.createObjectNode().put("commandId", commandId).put("companionId", companionId)
                .put("type", type).put("originalText", "mcac smoke " + type.toLowerCase());
        body.set("arguments", arguments == null ? JSON.createObjectNode() : arguments);
        return request(profile, "POST", "/commands", body.toString(), timeout);
    }

    JsonNode task(RuntimeProfile profile, String taskId, Duration timeout) throws IOException {
        String safe = URLEncoder.encode(taskId, StandardCharsets.UTF_8).replace("+", "%20");
        return request(profile, "GET", "/tasks/" + safe, null, timeout);
    }

    JsonNode agent(RuntimeProfile profile, String commandId, String companionId, String text,
                   boolean execute, Duration timeout) throws IOException {
        if (text == null || text.isBlank() || text.length() > 4096) throw new IllegalArgumentException("自然语言输入必须为 1..4096 字符");
        ObjectNode body = JSON.createObjectNode().put("commandId", commandId).put("companionId", companionId)
                .put("text", text).put("execute", execute);
        return request(profile, "POST", "/agent", body.toString(), timeout);
    }

    JsonNode brain(RuntimeProfile profile, String controllerId, String companionId, String text,
                   Duration timeout) throws IOException {
        if (text == null || text.isBlank() || text.length() > 4096) {
            throw new IllegalArgumentException("Brain input must be 1..4096 characters");
        }
        ObjectNode body = JSON.createObjectNode().put("controllerId", controllerId)
                .put("companionId", companionId).put("text", text);
        return request(profile, "POST", "/brain", body.toString(), timeout);
    }

    JsonNode inspect(RuntimeProfile profile, String path, Duration timeout) throws IOException {
        if (path == null || !path.startsWith("/") || path.contains("..") || path.contains("#")) {
            throw new IllegalArgumentException("Runtime inspection path is invalid");
        }
        return request(profile, "GET", path, null, timeout);
    }

    private JsonNode request(RuntimeProfile profile, String method, String path, String body, Duration timeout)
            throws IOException {
        String token = Files.readString(profile.profileDirectory().resolve("pairing.token"),
                StandardCharsets.US_ASCII).trim();
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + profile.healthPort() + path))
                .timeout(timeout).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        HttpRequest request = "POST".equals(method)
                ? builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build()
                : builder.GET().build();
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Runtime control returned HTTP " + response.statusCode());
            }
            return JSON.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Runtime control request was interrupted", interrupted);
        }
    }
}
