package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class SearchConfigurationService {
    private static final int MAX_TEST_RESPONSE_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern DOMAIN = Pattern.compile("(?i)[a-z0-9.-]{1,253}");

    Path file(RuntimeProfile profile) { return profile.profileDirectory().resolve("search.json"); }

    void configure(RuntimeProfile profile, String endpoint, String tokenEnv, int timeoutSeconds,
                   List<String> allowedDomains, List<String> deniedDomains) throws IOException {
        URI uri = validateEndpoint(endpoint);
        if (tokenEnv == null || !ENVIRONMENT.matcher(tokenEnv).matches()) {
            throw new IOException("Invalid Search token environment variable name");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 30) throw new IOException("Search timeout must be 1..30 seconds");
        List<String> allowed = domains(allowedDomains);
        List<String> denied = domains(deniedDomains);
        if (allowed.stream().anyMatch(denied::contains)) throw new IOException("Search allow and deny domains overlap");
        Files.createDirectories(profile.profileDirectory());
        var node = JSON.createObjectNode().put("mode", "http").put("endpoint", uri.toString())
                .put("tokenEnv", tokenEnv).put("timeoutSeconds", timeoutSeconds);
        allowed.forEach(node.putArray("allowedDomains")::add);
        denied.forEach(node.putArray("deniedDomains")::add);
        JSON.writerWithDefaultPrettyPrinter().writeValue(file(profile).toFile(), node);
    }

    void disable(RuntimeProfile profile) throws IOException {
        Files.createDirectories(profile.profileDirectory());
        var node = JSON.createObjectNode().put("mode", "disabled");
        node.putArray("allowedDomains"); node.putArray("deniedDomains");
        JSON.writerWithDefaultPrettyPrinter().writeValue(file(profile).toFile(), node);
    }

    JsonNode status(RuntimeProfile profile) throws IOException {
        JsonNode stored = Files.isRegularFile(file(profile)) ? JSON.readTree(file(profile).toFile())
                : JSON.createObjectNode().put("mode", "disabled");
        var status = JSON.createObjectNode().put("mode", stored.path("mode").asText("disabled"));
        if (stored.path("endpoint").isTextual()) status.put("endpoint", stored.path("endpoint").asText());
        if (stored.path("tokenEnv").isTextual()) status.put("tokenEnv", stored.path("tokenEnv").asText());
        if (stored.path("timeoutSeconds").canConvertToInt()) {
            status.put("timeoutSeconds", stored.path("timeoutSeconds").asInt());
        }
        copyTextArray(stored.path("allowedDomains"), status.putArray("allowedDomains"));
        copyTextArray(stored.path("deniedDomains"), status.putArray("deniedDomains"));
        return status;
    }

    TestResult test(RuntimeProfile profile) throws IOException {
        JsonNode configuration = status(profile);
        if (!"http".equals(configuration.path("mode").asText())) {
            return new TestResult(true, false, 0, "DISABLED", "Search is disabled; no network request was made");
        }
        String token = System.getenv(configuration.path("tokenEnv").asText("MC_COMPANION_SEARCH_TOKEN"));
        if (token == null || token.isBlank()) {
            return new TestResult(false, false, 0, "TOKEN_MISSING", "Search token environment variable is not set");
        }
        return testWithToken(configuration, token);
    }

    ConfigurationResult inspect(RuntimeProfile profile) throws IOException {
        JsonNode configuration = status(profile);
        String mode = configuration.path("mode").asText("disabled");
        if ("disabled".equals(mode)) {
            return new ConfigurationResult(true, mode, "not-required", "Search is disabled; no network access is allowed");
        }
        if (!"http".equals(mode)) throw new IOException("Unsupported Search mode");
        validateEndpoint(configuration.path("endpoint").asText());
        String environment = configuration.path("tokenEnv").asText("");
        if (!ENVIRONMENT.matcher(environment).matches()) throw new IOException("Invalid Search token environment variable name");
        int timeout = configuration.path("timeoutSeconds").asInt(0);
        if (timeout < 1 || timeout > 30) throw new IOException("Search timeout must be 1..30 seconds");
        List<String> allowed = domains(textValues(configuration.path("allowedDomains")));
        List<String> denied = domains(textValues(configuration.path("deniedDomains")));
        if (allowed.stream().anyMatch(denied::contains)) throw new IOException("Search allow and deny domains overlap");
        String token = System.getenv(environment);
        boolean available = token != null && !token.isBlank();
        return new ConfigurationResult(available, mode, environment, available
                ? "Search configuration and credential source are ready"
                : "Search is enabled but its token environment variable is missing");
    }

    TestResult testWithToken(JsonNode configuration, String token) {
        long started = System.nanoTime();
        try {
            URI endpoint = validateEndpoint(configuration.path("endpoint").asText());
            int timeout = configuration.path("timeoutSeconds").asInt(15);
            if (timeout < 1 || timeout > 30) throw new IOException("Search timeout must be 1..30 seconds");
            var body = JSON.createObjectNode().put("query", "MCAC Search Doctor connectivity probe")
                    .put("maxResults", 1).put("locale", "en").put("safeSearch", true);
            copyTextArray(configuration.path("allowedDomains"), body.putArray("allowedDomains"));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<java.io.InputStream> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.min(5, timeout))).followRedirects(HttpClient.Redirect.NEVER)
                    .build().send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var input = response.body()) { bytes = input.readNBytes(MAX_TEST_RESPONSE_BYTES + 1); }
            long latency = elapsed(started);
            if (bytes.length > MAX_TEST_RESPONSE_BYTES) {
                return new TestResult(false, true, latency, "RESPONSE_TOO_LARGE", "Search response exceeded 1 MiB");
            }
            if (response.statusCode() / 100 != 2) {
                return new TestResult(false, true, latency, "HTTP_" + response.statusCode(),
                        "Search provider returned HTTP " + response.statusCode());
            }
            JsonNode result;
            try { result = JSON.readTree(bytes); }
            catch (IOException invalid) {
                return new TestResult(false, true, latency, "INVALID_JSON", "Search provider returned invalid JSON");
            }
            JsonNode results = result.path("results");
            if (!results.isArray() || results.size() > 1) {
                return new TestResult(false, true, latency, "INVALID_RESULTS", "Search provider response contract is invalid");
            }
            return new TestResult(true, true, latency, "OK", "Search provider accepted the bounded Doctor query");
        } catch (java.net.http.HttpTimeoutException timeout) {
            return new TestResult(false, true, elapsed(started), "TIMEOUT", "Search provider request timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new TestResult(false, true, elapsed(started), "INTERRUPTED", "Search provider test was interrupted");
        } catch (Exception failure) {
            return new TestResult(false, true, elapsed(started), "CONNECTION_FAILED",
                    "Search provider connection failed: " + failure.getClass().getSimpleName());
        }
    }

    private static URI validateEndpoint(String value) throws IOException {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getFragment() != null) {
                throw new IOException("Search endpoint must be an absolute URL without credentials, query, or fragment");
            }
            boolean loopback = InetAddress.getByName(uri.getHost()).isLoopbackAddress();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    && !(loopback && "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IOException("Search endpoint must use HTTPS (HTTP is allowed only for loopback testing)");
            }
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Search endpoint is invalid", invalid);
        }
    }

    private static List<String> domains(List<String> values) throws IOException {
        if (values == null) return List.of();
        if (values.size() > 64) throw new IOException("Search domain policy supports at most 64 entries");
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String raw : values) {
            String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
            if (!DOMAIN.matcher(value).matches()) throw new IOException("Invalid Search policy domain");
            if (!result.contains(value)) result.add(value);
        }
        return List.copyOf(result);
    }

    private static void copyTextArray(JsonNode source, com.fasterxml.jackson.databind.node.ArrayNode target) {
        if (!source.isArray()) return;
        source.forEach(value -> { if (value.isTextual()) target.add(value.asText()); });
    }

    private static List<String> textValues(JsonNode source) {
        if (!source.isArray()) return List.of();
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        source.forEach(value -> { if (value.isTextual()) values.add(value.asText()); });
        return List.copyOf(values);
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    record TestResult(boolean success, boolean networkAttempted, long latencyMillis, String code, String message) { }
    record ConfigurationResult(boolean healthy, String mode, String tokenEnvironment, String message) { }
}
