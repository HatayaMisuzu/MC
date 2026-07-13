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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

final class ProviderConfigurationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern MODEL = Pattern.compile("[^\\p{Cntrl}]{1,256}");

    Path file(RuntimeProfile profile) { return profile.profileDirectory().resolve("provider.json"); }

    void configure(RuntimeProfile profile, String baseUrl, String model, String environment) throws IOException {
        configure(profile, baseUrl, model, environment, 15);
    }

    void configure(RuntimeProfile profile, String baseUrl, String model, String environment, int timeoutSeconds)
            throws IOException {
        URI uri = validateBaseUrl(baseUrl);
        if (model == null || !MODEL.matcher(model.strip()).matches()) throw new IOException("Invalid provider model name");
        if (environment == null || !ENVIRONMENT.matcher(environment).matches()) {
            throw new IOException("Invalid API key environment variable name");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) throw new IOException("Provider timeout must be 1..300 seconds");
        Files.createDirectories(profile.profileDirectory());
        ObjectNode node = JSON.createObjectNode().put("mode", "openai-compatible")
                .put("baseUrl", trimSlash(uri.toString())).put("model", model.strip())
                .put("apiKeyEnv", environment).put("timeoutSeconds", timeoutSeconds);
        JSON.writerWithDefaultPrettyPrinter().writeValue(file(profile).toFile(), node);
    }

    void disable(RuntimeProfile profile) throws IOException {
        Files.createDirectories(profile.profileDirectory());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file(profile).toFile(),
                JSON.createObjectNode().put("mode", "rules"));
    }

    JsonNode status(RuntimeProfile profile) throws IOException {
        return Files.isRegularFile(file(profile)) ? JSON.readTree(file(profile).toFile())
                : JSON.createObjectNode().put("mode", "rules");
    }

    TestResult test(RuntimeProfile profile) throws IOException {
        JsonNode node = status(profile);
        if (node.path("mode").asText("rules").equals("rules")) {
            return new TestResult(true, 0, "rules", "No API key required");
        }
        String environment = node.path("apiKeyEnv").asText("MC_COMPANION_API_KEY");
        String key = System.getenv(environment);
        if (key == null || key.isBlank()) {
            return new TestResult(false, 0, node.path("model").asText(),
                    "API key environment variable is not set");
        }
        return testWithKey(node, key);
    }

    TestResult testWithKey(JsonNode node, String key) {
        String model = node.path("model").asText("");
        int timeoutSeconds = node.path("timeoutSeconds").asInt(15);
        long started = System.nanoTime();
        try {
            URI uri = URI.create(trimSlash(validateBaseUrl(node.path("baseUrl").asText()).toString())
                    + "/chat/completions");
            ObjectNode body = JSON.createObjectNode().put("model", model).put("max_tokens", 1);
            body.putArray("messages").addObject().put("role", "user").put("content", "ping");
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                    .send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds))
                            .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                            HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - started) / 1_000_000;
            String message = switch (response.statusCode()) {
                case 401 -> "HTTP 401: credentials rejected";
                case 403 -> "HTTP 403: provider access denied";
                case 404 -> "HTTP 404: endpoint or model not found";
                case 429 -> "HTTP 429: provider rate limited";
                default -> response.statusCode() >= 500 ? "HTTP " + response.statusCode() + ": provider unavailable"
                        : "HTTP " + response.statusCode();
            };
            if (response.statusCode() / 100 != 2) return new TestResult(false, latency, model, message);
            JsonNode responseJson;
            try { responseJson = JSON.readTree(response.body()); }
            catch (IOException nonJson) { return new TestResult(false, latency, model, "Provider returned non-JSON success response"); }
            boolean valid = responseJson.has("choices") || responseJson.has("id") || responseJson.has("output");
            return new TestResult(valid, latency, model,
                    valid ? "Provider accepted URL and model" : "Provider JSON response lacks completion fields");
        } catch (java.net.http.HttpTimeoutException timeout) {
            return new TestResult(false, elapsed(started), model, "Provider request timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new TestResult(false, elapsed(started), model, "Provider test interrupted");
        } catch (Exception failure) {
            return new TestResult(false, elapsed(started), model,
                    "Provider connection failed: " + failure.getClass().getSimpleName());
        }
    }

    private static URI validateBaseUrl(String value) throws IOException {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IOException("Provider base URL must be an absolute URL without credentials, query or fragment");
            }
            boolean loopback = InetAddress.getByName(uri.getHost()).isLoopbackAddress();
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !(loopback && "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IOException("Provider base URL must use HTTPS (HTTP is allowed only for loopback testing)");
            }
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Provider base URL is invalid", invalid);
        }
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private static String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    record TestResult(boolean success, long latencyMillis, String model, String message) { }
}
