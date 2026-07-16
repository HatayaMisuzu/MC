package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mccompanion.terminal.runtime.RuntimeProfile;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class SearchConfigurationService {
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
}
