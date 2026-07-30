package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.terminal.runtime.RuntimeProfile;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Independent external-Brain configuration and protocol-aware connectivity probe. */
final class BrainConfigurationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern MODEL = Pattern.compile("[^\\p{Cntrl}]{1,256}");
    private static final Set<String> MODES = Set.of("disabled", "hermes", "openai-compatible");

    Path file(RuntimeProfile profile) {
        return profile.profileDirectory().resolve("brain.json");
    }

    void configure(RuntimeProfile profile, String mode, String endpoint, String tokenEnv, String model,
                   int timeoutSeconds, int maxToolCallsPerTurn, int maxOutputTokens,
                   int maxRequests, int maxInputTokens, int maxTotalOutputTokens,
                   int maxWallClockMinutes, int maxRetries) throws IOException {
        String normalizedMode = mode == null ? "" : mode.strip().toLowerCase();
        if (!MODES.contains(normalizedMode) || normalizedMode.equals("disabled")) {
            throw new IOException("Brain mode must be hermes or openai-compatible when configuring");
        }
        URI uri = validateEndpoint(endpoint);
        if (tokenEnv == null || !ENVIRONMENT.matcher(tokenEnv).matches()) {
            throw new IOException("Invalid Brain token environment variable name");
        }
        String normalizedModel = model == null ? "" : model.strip();
        if (normalizedMode.equals("openai-compatible")
                && !MODEL.matcher(normalizedModel).matches()) {
            throw new IOException("Invalid Brain model name");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IOException("Brain timeout must be 1..300 seconds");
        }
        if (maxToolCallsPerTurn < 1 || maxToolCallsPerTurn > 32) {
            throw new IOException("Brain Tool budget must be 1..32");
        }
        if (maxOutputTokens < 128 || maxOutputTokens > 4096) {
            throw new IOException("Brain output budget must be 128..4096 tokens");
        }
        bounded(maxRequests, 1, 1_000, "Brain request budget must be 1..1000");
        bounded(maxInputTokens, 128, 2_000_000, "Brain input budget must be 128..2000000");
        bounded(maxTotalOutputTokens, 128, 500_000,
                "Brain total output budget must be 128..500000");
        bounded(maxWallClockMinutes, 1, 480, "Brain wall clock budget must be 1..480 minutes");
        bounded(maxRetries, 0, 5, "Brain retry budget must be 0..5");
        Files.createDirectories(profile.profileDirectory());
        ObjectNode node = JSON.createObjectNode().put("mode", normalizedMode)
                .put("endpoint", trimSlash(uri.toString()))
                .put("tokenEnv", tokenEnv)
                .put("model", normalizedMode.equals("hermes") ? "hermes" : normalizedModel)
                .put("timeoutSeconds", timeoutSeconds)
                .put("maxToolCallsPerTurn", maxToolCallsPerTurn)
                .put("maxOutputTokens", maxOutputTokens)
                .put("maxRequests", maxRequests)
                .put("maxInputTokens", maxInputTokens)
                .put("maxTotalOutputTokens", maxTotalOutputTokens)
                .put("maxWallClockMinutes", maxWallClockMinutes)
                .put("maxRetries", maxRetries);
        JSON.writerWithDefaultPrettyPrinter().writeValue(file(profile).toFile(), node);
    }

    void disable(RuntimeProfile profile) throws IOException {
        Files.createDirectories(profile.profileDirectory());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file(profile).toFile(),
                JSON.createObjectNode().put("mode", "disabled"));
    }

    JsonNode status(RuntimeProfile profile) throws IOException {
        return Files.isRegularFile(file(profile)) ? JSON.readTree(file(profile).toFile())
                : JSON.createObjectNode().put("mode", "disabled");
    }

    TestResult test(RuntimeProfile profile) throws IOException {
        JsonNode config = status(profile);
        String mode = config.path("mode").asText("disabled");
        if (mode.equals("disabled")) {
            return new TestResult(false, "DISABLED", 0, mode, "External Brain is disabled");
        }
        String tokenEnv = config.path("tokenEnv").asText("MCAC_BRAIN_TOKEN");
        String token = System.getenv(tokenEnv);
        if (token == null || token.isBlank()) {
            return new TestResult(false, "CREDENTIAL_MISSING", 0, mode,
                    "Brain token environment variable is not set");
        }
        return testWithToken(config, token);
    }

    TestResult testWithToken(JsonNode config, String token) {
        String mode = config.path("mode").asText("");
        int timeoutSeconds = config.path("timeoutSeconds").asInt(60);
        long started = System.nanoTime();
        try {
            return mode.equals("hermes")
                    ? testHermes(config, token, timeoutSeconds, started)
                    : testOpenAi(config, token, timeoutSeconds, started);
        } catch (java.net.http.HttpTimeoutException timeout) {
            return result(false, "UNAVAILABLE", started, mode, "Brain request timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return result(false, "UNAVAILABLE", started, mode, "Brain test interrupted");
        } catch (Exception failure) {
            return result(false, "UNAVAILABLE", started, mode,
                    "Brain connection failed: " + failure.getClass().getSimpleName());
        }
    }

    private TestResult testOpenAi(JsonNode config, String token, int timeoutSeconds, long started)
            throws Exception {
        String configured = trimSlash(validateEndpoint(config.path("endpoint").asText()).toString());
        URI uri = URI.create(configured.endsWith("/chat/completions")
                ? configured : configured + "/chat/completions");
        ObjectNode body = JSON.createObjectNode().put("model", config.path("model").asText(""))
                .put("max_tokens", 32);
        body.putArray("messages").addObject().put("role", "user")
                .put("content", "Call mcac_health_probe exactly once.");
        ObjectNode function = body.putArray("tools").addObject().put("type", "function")
                .putObject("function");
        function.put("name", "mcac_health_probe")
                .put("description", "Verify required MCAC tool-calling support");
        function.set("parameters", JSON.createObjectNode().put("type", "object")
                .put("additionalProperties", false));
        ObjectNode choice = JSON.createObjectNode().put("type", "function");
        choice.set("function", JSON.createObjectNode().put("name", "mcac_health_probe"));
        body.set("tool_choice", choice);
        HttpResponse<byte[]> response = send(uri, token, timeoutSeconds, body);
        if (response.statusCode() / 100 != 2) {
            return httpFailure(started, modeLabel(config), response.statusCode());
        }
        JsonNode calls = parseBounded(response.body()).path("choices").path(0)
                .path("message").path("tool_calls");
        boolean verified = calls.isArray() && calls.size() == 1
                && calls.path(0).path("function").path("name").asText().equals("mcac_health_probe");
        return result(verified, verified ? "TOOL_CALL_VERIFIED" : "PROTOCOL_INCOMPATIBLE",
                started, modeLabel(config), verified
                        ? "OpenAI-compatible MCAC Tool call verified"
                        : "Endpoint is reachable but did not return the required Tool call");
    }

    private TestResult testHermes(JsonNode config, String token, int timeoutSeconds, long started)
            throws Exception {
        URI base = URI.create(trimSlash(validateEndpoint(config.path("endpoint").asText()).toString()) + "/");
        String controllerId = "terminal-health-" + UUID.randomUUID();
        ObjectNode open = JSON.createObjectNode().put("protocol", "mcac-brain/1")
                .put("controllerId", controllerId).put("companionId", "terminal-health-probe");
        open.set("context", JSON.createObjectNode());
        ObjectNode probe = JSON.createObjectNode().put("name", "mcac_health_probe")
                .put("version", "1.0").put("description", "Return one MCAC protocol Tool call")
                .put("risk", "LOW").put("permission", "READ_WORLD")
                .put("timeout", "PT5S").put("idempotent", true);
        probe.set("inputSchema", JSON.createObjectNode().put("type", "object")
                .put("additionalProperties", false));
        open.putArray("tools").add(probe);
        HttpResponse<byte[]> opened = send(base.resolve("sessions"), token, timeoutSeconds, open);
        if (opened.statusCode() / 100 != 2) {
            return httpFailure(started, modeLabel(config), opened.statusCode());
        }
        String sessionId = parseBounded(opened.body()).path("sessionId").asText("");
        if (!sessionId.matches("[A-Za-z0-9_-]{8,128}")) {
            return result(false, "PROTOCOL_INCOMPATIBLE", started, modeLabel(config),
                    "Hermes session response is invalid");
        }
        TestResult probeResult = null;
        Exception probeFailure = null;
        try {
            ObjectNode turn = JSON.createObjectNode().put("protocol", "mcac-brain/1")
                    .put("userMessage", "Call mcac_health_probe exactly once")
                    .put("remainingToolCalls", 1);
            turn.set("context", JSON.createObjectNode());
            turn.putArray("toolResults");
            HttpResponse<byte[]> turned = send(base.resolve("sessions/" + sessionId + "/turns"),
                    token, timeoutSeconds, turn);
            if (turned.statusCode() / 100 != 2) {
                probeResult = httpFailure(started, modeLabel(config), turned.statusCode());
            } else {
                JsonNode reply = parseBounded(turned.body());
                boolean verified = reply.path("kind").asText().equals("TOOL_CALLS")
                        && reply.path("toolCalls").isArray()
                        && reply.path("toolCalls").size() == 1
                        && reply.path("toolCalls").path(0).path("name").asText()
                                .equals("mcac_health_probe");
                probeResult = result(verified, verified ? "TOOL_CALL_VERIFIED" : "PROTOCOL_INCOMPATIBLE",
                        started, modeLabel(config), verified
                                ? "Hermes mcac-brain/1 Tool call verified"
                                : "Hermes is reachable but did not complete the MCAC Tool-call probe");
            }
        } catch (Exception failure) {
            probeFailure = failure;
        }
        ProbeCleanup cleanup = cancelHermesProbe(base, sessionId, token, timeoutSeconds);
        if (!cleanup.success()) {
            return result(false, "SESSION_CLEANUP_FAILED", started, modeLabel(config),
                    cleanup.message());
        }
        if (probeFailure != null) throw probeFailure;
        return probeResult;
    }

    private static ProbeCleanup cancelHermesProbe(
            URI base, String sessionId, String token, int timeoutSeconds) {
        try {
            HttpResponse<byte[]> response = send(
                    base.resolve("sessions/" + sessionId + "/cancel"), token, timeoutSeconds,
                    JSON.createObjectNode().put("protocol", "mcac-brain/1")
                            .put("reason", "TERMINAL_HEALTH_PROBE_COMPLETE"));
            if (response.statusCode() / 100 != 2) {
                return new ProbeCleanup(false,
                        "Hermes health-session cleanup returned HTTP " + response.statusCode());
            }
            return new ProbeCleanup(true, "");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ProbeCleanup(false, "Hermes health-session cleanup was interrupted");
        } catch (IOException failure) {
            return new ProbeCleanup(false,
                    "Hermes health-session cleanup failed: "
                            + failure.getClass().getSimpleName());
        }
    }

    private record ProbeCleanup(boolean success, String message) {}

    private static HttpResponse<byte[]> send(URI uri, String token, int timeoutSeconds, JsonNode body)
            throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(5, timeoutSeconds)))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private static JsonNode parseBounded(byte[] value) throws IOException {
        if (value.length > MAX_RESPONSE_BYTES) throw new IOException("Brain response exceeded 1 MiB");
        return JSON.readTree(value);
    }

    private static TestResult httpFailure(long started, String adapter, int status) {
        String state = status == 401 || status == 403 ? "CREDENTIAL_MISSING" : "UNAVAILABLE";
        return result(false, state, started, adapter, "HTTP " + status);
    }

    private static TestResult result(boolean success, String status, long started,
                                     String adapter, String message) {
        return new TestResult(success, status, (System.nanoTime() - started) / 1_000_000,
                adapter, message);
    }

    private static String modeLabel(JsonNode config) {
        return config.path("mode").asText("");
    }

    private static URI validateEndpoint(String value) throws IOException {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IOException("Brain endpoint must be an absolute URL without credentials, query or fragment");
            }
            boolean loopback = InetAddress.getByName(uri.getHost()).isLoopbackAddress();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    && !(loopback && "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IOException("Brain endpoint must use HTTPS (HTTP is allowed only for loopback testing)");
            }
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Brain endpoint is invalid", invalid);
        }
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static int bounded(int value, int minimum, int maximum, String message) throws IOException {
        if (value < minimum || value > maximum) throw new IOException(message);
        return value;
    }

    record TestResult(boolean success, String status, long latencyMillis,
                      String adapter, String message) { }
}
