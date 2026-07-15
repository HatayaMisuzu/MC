package com.mccompanion.runtime.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Bounded real-provider implementation. It never uses browser state, cookies, forms, or JavaScript. */
public final class HttpSearchProvider implements SearchProvider {
    private static final int MAX_API_BYTES = 1_048_576;
    private static final int MAX_PAGE_BYTES = 1_048_576;
    private final URI queryEndpoint;
    private final String token;
    private final HttpClient client;

    public HttpSearchProvider(String endpoint, String token, Duration connectTimeout) {
        this.queryEndpoint = requireProviderEndpoint(endpoint);
        if (token == null || token.isBlank()) throw new IllegalArgumentException("search provider token is required");
        this.token = token;
        Duration timeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public List<SearchSource> query(SearchQuery request) {
        ObjectNode body = Json.object().put("query", request.query()).put("maxResults", request.maxResults())
                .put("locale", request.locale()).put("safeSearch", request.safeSearch());
        if (request.recencyDays() != null) body.put("recencyDays", request.recencyDays());
        request.allowedDomains().forEach(body.putArray("allowedDomains")::add);
        JsonNode response = apiPost(queryEndpoint, body, request.timeout());
        JsonNode results = response.path("results");
        if (!results.isArray() || results.size() > request.maxResults()) throw new IllegalStateException("SEARCH_INVALID_RESULTS");
        List<SearchSource> sources = new ArrayList<>();
        Instant retrieved = Instant.now();
        for (JsonNode result : results) {
            String id = required(result, "sourceId", 128);
            if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalStateException("SEARCH_INVALID_SOURCE_ID");
            URI url = SearchSecurity.requirePublicHttps(required(result, "url", 2048), request.allowedDomains());
            String domain = SearchSecurity.normalizedDomain(url.getHost());
            sources.add(new SearchSource(id, required(result, "title", 512), url.toString(), domain,
                    result.path("publisher").asText(""), parseInstant(result.path("publishedAt").asText("")),
                    retrieved, limited(result.path("snippet").asText(""), 2048),
                    result.path("trustLevel").asText("UNVERIFIED"), result.path("contentType").asText("text/html")));
        }
        return List.copyOf(sources);
    }

    @Override public SearchPage open(SearchSource source, SearchQuery policy) {
        URI uri = SearchSecurity.requirePublicHttps(source.url(), policy.allowedDomains());
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(policy.timeout())
                .header("Accept", "text/html,text/plain;q=0.9").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("SEARCH_OPEN_HTTP_" + response.statusCode());
                }
                String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
                if (!(type.startsWith("text/html") || type.startsWith("text/plain"))) {
                    throw new IllegalStateException("SEARCH_CONTENT_TYPE_DENIED");
                }
                byte[] bytes = input.readNBytes(MAX_PAGE_BYTES + 1);
                if (bytes.length > MAX_PAGE_BYTES) throw new IllegalStateException("SEARCH_PAGE_TOO_LARGE");
                return sanitizePage(source, type, new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("SEARCH_OPEN_IO_ERROR", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SEARCH_OPEN_INTERRUPTED", failure);
        }
    }

    @Override public void close() { client.close(); }

    static SearchPage sanitizePage(SearchSource source, String contentType, String raw) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        String text = type.startsWith("text/html") ? Jsoup.parse(raw, source.url()).text() : raw;
        text = limited(text.replaceAll("\\s+", " ").strip(), 64_000);
        boolean injection = text.matches("(?is).*(ignore (all|previous) (instructions|rules)|system prompt|call (a )?tool|developer message).*" );
        return new SearchPage(source.sourceId(), source.title(), source.url(), source.domain(),
                "UNTRUSTED_EXTERNAL_CONTENT\n" + text, type, injection, Instant.now());
    }

    private JsonNode apiPost(URI endpoint, ObjectNode body, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(MAX_API_BYTES + 1);
                if (bytes.length > MAX_API_BYTES) throw new IllegalStateException("SEARCH_RESPONSE_TOO_LARGE");
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("SEARCH_HTTP_" + response.statusCode());
                }
                return Json.parse(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("SEARCH_IO_ERROR", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SEARCH_INTERRUPTED", failure);
        }
    }

    static URI requireProviderEndpoint(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("search endpoint is required");
        URI uri = URI.create(value.strip());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("search endpoint must be HTTP(S) without user info");
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            try {
                if (!InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
                    throw new IllegalArgumentException("non-loopback search endpoint requires HTTPS");
                }
            } catch (IOException failure) { throw new IllegalArgumentException("search endpoint host is invalid", failure); }
        }
        return uri;
    }

    private static String required(JsonNode node, String field, int limit) {
        String value = node.path(field).asText("").strip();
        if (value.isBlank() || value.length() > limit) throw new IllegalStateException("SEARCH_INVALID_" + field.toUpperCase());
        return value;
    }
    private static String limited(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit); }
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (java.time.format.DateTimeParseException ignored) { return null; }
    }
}
