package com.mccompanion.runtime.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.conversation.ConversationOption;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Adapter for a separately running Hermes controller using the bounded MCAC Brain Bridge protocol. */
public final class HermesBrainAdapter implements ExternalBrainAdapter {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private final URI base;
    private final String bearerToken;
    private final Duration timeout;
    private final HttpClient client;

    public HermesBrainAdapter(String baseUrl, String bearerToken, Duration timeout) {
        this.base = validateBase(baseUrl);
        if (bearerToken == null || bearerToken.isBlank()) throw new IllegalArgumentException("Hermes bearer token is required");
        this.bearerToken = bearerToken;
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public BrainSession openSession(BrainSessionRequest request) {
        ObjectNode body = Json.object().put("protocol", "mcac-brain/1")
                .put("controllerId", request.controllerId()).put("companionId", request.companionId());
        var assembled = BoundedBrainContextAssembler.assemble(request.context());
        body.set("context", assembled.context());
        body.set("contextBudget", assembled.clippingStats());
        body.set("tools", Json.MAPPER.valueToTree(request.tools()));
        JsonNode response = post("sessions", body);
        String sessionId = required(response, "sessionId");
        if (!sessionId.matches("[A-Za-z0-9_-]{8,128}")) throw new IllegalStateException("HERMES_INVALID_SESSION_ID");
        return new BrainSession(sessionId, request.controllerId(), request.companionId(), Instant.now());
    }

    @Override public boolean supportsResume() { return true; }

    @Override public BrainSession resumeSession(BrainSessionRequest request, String sessionId) {
        requireSessionId(sessionId);
        ObjectNode body = Json.object().put("protocol", "mcac-brain/1")
                .put("controllerId", request.controllerId()).put("companionId", request.companionId());
        var assembled = BoundedBrainContextAssembler.assemble(request.context());
        body.set("context", assembled.context());
        body.set("contextBudget", assembled.clippingStats());
        body.set("tools", Json.MAPPER.valueToTree(request.tools()));
        JsonNode response = post("sessions/" + sessionId + "/resume", body);
        if (!response.path("resumed").asBoolean(false)) throw new IllegalStateException("HERMES_RESUME_REJECTED");
        return new BrainSession(sessionId, request.controllerId(), request.companionId(), Instant.now());
    }

    @Override public BrainTurnResult continueTurn(BrainTurnRequest request) {
        requireSessionId(request.sessionId());
        ObjectNode body = Json.object().put("protocol", "mcac-brain/1")
                .put("userMessage", request.userMessage()).put("remainingToolCalls", request.remainingToolCalls());
        var assembled = BoundedBrainContextAssembler.assemble(request.context());
        body.set("context", assembled.context());
        body.set("contextBudget", assembled.clippingStats());
        var results = body.putArray("toolResults");
        for (var result : request.toolResults()) results.add(BoundedBrainContextAssembler.bounded(
                Json.MAPPER.valueToTree(result), 8_192, Json.object(), "toolResult"));
        JsonNode response = post("sessions/" + request.sessionId() + "/turns", body);
        BrainTurnResult.Kind kind;
        try { kind = BrainTurnResult.Kind.valueOf(required(response, "kind")); }
        catch (IllegalArgumentException failure) { throw new IllegalStateException("HERMES_INVALID_RESULT_KIND", failure); }
        if (kind == BrainTurnResult.Kind.TOOL_CALLS) {
            JsonNode calls = response.path("toolCalls");
            if (!calls.isArray() || calls.isEmpty() || calls.size() > 16) {
                throw new IllegalStateException("HERMES_INVALID_TOOL_CALLS");
            }
            List<ToolCall> values = new ArrayList<>();
            for (JsonNode call : calls) {
                values.add(new ToolCall(required(call, "callId"), required(call, "name"), call.path("arguments")));
            }
            return BrainTurnResult.tools(values);
        }
        if (kind == BrainTurnResult.Kind.ASK_USER) {
            JsonNode question = response.path("question");
            JsonNode options = question.path("options");
            if (!question.isObject() || !options.isArray() || options.isEmpty() || options.size() > 3) {
                throw new IllegalStateException("HERMES_INVALID_QUESTION");
            }
            List<ConversationOption> values = new ArrayList<>();
            for (JsonNode option : options) {
                values.add(new ConversationOption(required(option, "id"), required(option, "label"),
                        option.path("description").asText("")));
            }
            BrainQuestion structured = new BrainQuestion(required(question, "prompt"), required(question, "reason"),
                    values, question.path("freeTextAllowed").asBoolean(false), question.path("context"),
                    question.path("taskId").asText(null));
            return BrainTurnResult.askUser(structured);
        }
        return new BrainTurnResult(kind, response.path("response").asText(""), List.of(),
                response.path("reason").asText(""));
    }

    @Override public void cancel(String sessionId, String reason) {
        requireSessionId(sessionId);
        ObjectNode body = Json.object().put("protocol", "mcac-brain/1").put("reason", reason == null ? "CANCELLED" : reason);
        try { post("sessions/" + sessionId + "/cancel", body); }
        catch (RuntimeException ignored) { /* local cancellation still wins; remote cleanup is best effort */ }
    }

    @Override public BrainHealth health() {
        return new BrainHealth("CONFIGURED", "hermes", "live health not yet checked", Instant.now());
    }

    @Override public void close() { client.close(); }

    private JsonNode post(String relative, ObjectNode body) {
        URI target = base.resolve(relative);
        HttpRequest request = HttpRequest.newBuilder(target).timeout(timeout)
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("HERMES_RESPONSE_TOO_LARGE");
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HERMES_HTTP_" + response.statusCode());
                }
                return Json.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("HERMES_IO_ERROR", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HERMES_INTERRUPTED", failure);
        }
    }

    private static URI validateBase(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Hermes endpoint is required");
        URI uri = URI.create(value.strip());
        if (uri.getUserInfo() != null || uri.getHost() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Hermes endpoint must be HTTP(S) without user info");
        }
        if (uri.getScheme().equalsIgnoreCase("http")) {
            try {
                if (!InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
                    throw new IllegalArgumentException("non-loopback Hermes endpoints require HTTPS");
                }
            } catch (IOException failure) { throw new IllegalArgumentException("Hermes host could not be resolved", failure); }
        }
        String path = uri.getPath() == null ? "/" : uri.getPath();
        if (!path.endsWith("/")) path += "/";
        try { return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), path, null, null); }
        catch (java.net.URISyntaxException failure) { throw new IllegalArgumentException("Hermes endpoint is invalid", failure); }
    }

    private static void requireSessionId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{8,128}")) throw new IllegalArgumentException("invalid session id");
    }

    private static String required(JsonNode value, String field) {
        String result = value.path(field).asText("").strip();
        if (result.isBlank() || result.length() > 256) throw new IllegalStateException("HERMES_INVALID_" + field.toUpperCase());
        return result;
    }
}
