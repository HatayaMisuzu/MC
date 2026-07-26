package com.mccompanion.runtime.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded real-provider implementation. It never uses browser state, cookies, forms, or JavaScript. */
public final class HttpSearchProvider implements SearchProvider {
    private static final int MAX_API_BYTES = 1_048_576;
    private static final int MAX_PAGE_BYTES = 1_048_576;
    private static final int MAX_REDIRECTS = 4;
    private final String queryEndpoint;
    private final String token;
    private final Duration connectTimeout;
    private final SearchSecurity.Resolver resolver;
    private final SearchHttpTransport transport;

    public HttpSearchProvider(String endpoint, String token, Duration connectTimeout) {
        this(endpoint, token, connectTimeout,
                host -> List.copyOf(java.util.Arrays.asList(java.net.InetAddress.getAllByName(host))),
                new PinnedHttpTransport());
    }

    HttpSearchProvider(String endpoint, String token, Duration connectTimeout,
                       SearchSecurity.Resolver resolver, SearchHttpTransport transport) {
        requireProviderEndpoint(endpoint, resolver);
        if (token == null || token.isBlank()) throw new IllegalArgumentException("search provider token is required");
        this.queryEndpoint = endpoint.strip();
        this.token = token;
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        this.resolver = resolver;
        this.transport = transport;
    }

    @Override public List<SearchSource> query(SearchQuery request) {
        ObjectNode body = Json.object().put("query", request.query()).put("maxResults", request.maxResults())
                .put("locale", request.locale()).put("safeSearch", request.safeSearch());
        if (request.recencyDays() != null) body.put("recencyDays", request.recencyDays());
        request.allowedDomains().forEach(body.putArray("allowedDomains")::add);
        JsonNode response = apiPost(body, request.timeout());
        JsonNode results = response.path("results");
        if (!results.isArray() || results.size() > request.maxResults()) throw new IllegalStateException("SEARCH_INVALID_RESULTS");
        List<SearchSource> sources = new ArrayList<>();
        Instant retrieved = Instant.now();
        for (JsonNode result : results) {
            String id = required(result, "sourceId", 128);
            if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalStateException("SEARCH_INVALID_SOURCE_ID");
            URI url = SearchSecurity.resolvePublicHttps(required(result, "url", 2048),
                    request.allowedDomains(), resolver).uri();
            String domain = SearchSecurity.normalizedDomain(url.getHost());
            sources.add(new SearchSource(id, required(result, "title", 512), url.toString(), domain,
                    result.path("publisher").asText(""), parseInstant(result.path("publishedAt").asText("")),
                    retrieved, limited(result.path("snippet").asText(""), 2048),
                    result.path("trustLevel").asText("UNVERIFIED"), result.path("contentType").asText("text/html")));
        }
        return List.copyOf(sources);
    }

    @Override public SearchPage open(SearchSource source, SearchQuery policy) {
        String current = source.url();
        Set<String> visited = new LinkedHashSet<>();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            SearchSecurity.ResolvedTarget target =
                    SearchSecurity.resolvePublicHttps(current, policy.allowedDomains(), resolver);
            if (!visited.add(target.uri().normalize().toString())) {
                throw new IllegalStateException("SEARCH_REDIRECT_LOOP");
            }
            SearchHttpTransport.Response response = send(target, "GET",
                    Map.of("Accept", "text/html,text/plain;q=0.9"), new byte[0],
                    policy.timeout(), MAX_PAGE_BYTES);
            if (isRedirect(response.status())) {
                String location = response.header("location");
                if (location == null || location.isBlank()) throw new IllegalStateException("SEARCH_REDIRECT_INVALID");
                if (redirects == MAX_REDIRECTS) throw new IllegalStateException("SEARCH_TOO_MANY_REDIRECTS");
                current = target.uri().resolve(location).toString();
                continue;
            }
            if (response.status() < 200 || response.status() >= 300) {
                throw new IllegalStateException("SEARCH_OPEN_HTTP_" + response.status());
            }
            String type = response.header("content-type");
            type = type == null ? "" : type.toLowerCase();
            if (!(type.startsWith("text/html") || type.startsWith("text/plain"))) {
                throw new IllegalStateException("SEARCH_CONTENT_TYPE_DENIED");
            }
            return sanitizePage(source, type, new String(response.body(), StandardCharsets.UTF_8));
        }
        throw new IllegalStateException("SEARCH_TOO_MANY_REDIRECTS");
    }

    @Override public void close() {
        try { transport.close(); } catch (Exception ignored) { }
    }

    static SearchPage sanitizePage(SearchSource source, String contentType, String raw) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        String text = type.startsWith("text/html") ? Jsoup.parse(raw, source.url()).text() : raw;
        text = limited(text.replaceAll("\\s+", " ").strip(), 64_000);
        boolean injection = text.matches("(?is).*(ignore (all|previous) (instructions|rules)|system prompt|call (a )?tool|developer message).*" );
        return new SearchPage(source.sourceId(), source.title(), source.url(), source.domain(),
                "UNTRUSTED_EXTERNAL_CONTENT\n" + text, type, injection, Instant.now());
    }

    private JsonNode apiPost(ObjectNode body, Duration timeout) {
        SearchSecurity.ResolvedTarget target = SearchSecurity.resolveProvider(queryEndpoint, resolver);
        SearchHttpTransport.Response response = send(target, "POST",
                Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json"),
                Json.write(body).getBytes(StandardCharsets.UTF_8), timeout, MAX_API_BYTES);
        // Never forward a provider credential across a redirect.
        if (isRedirect(response.status())) throw new IllegalStateException("SEARCH_PROVIDER_REDIRECT_DENIED");
        if (response.status() < 200 || response.status() >= 300) {
            throw new IllegalStateException("SEARCH_HTTP_" + response.status());
        }
        return Json.parse(new String(response.body(), StandardCharsets.UTF_8));
    }

    private SearchHttpTransport.Response send(SearchSecurity.ResolvedTarget target, String method,
                                              Map<String, String> headers, byte[] body,
                                              Duration requestTimeout, int maximumBytes) {
        Duration timeout = requestTimeout == null ? connectTimeout
                : requestTimeout.compareTo(connectTimeout) < 0 ? requestTimeout : connectTimeout;
        try {
            return transport.send(target, method, headers, body, timeout, maximumBytes);
        } catch (IOException failure) {
            throw new IllegalStateException(failure.getMessage() == null ? "SEARCH_IO_ERROR" : failure.getMessage(), failure);
        }
    }

    static URI requireProviderEndpoint(String value) {
        return SearchSecurity.resolveProvider(value).uri();
    }

    static URI requireProviderEndpoint(String value, SearchSecurity.Resolver resolver) {
        return SearchSecurity.resolveProvider(value, resolver).uri();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String required(JsonNode node, String field, int limit) {
        String value = node.path(field).asText("").strip();
        if (value.isBlank() || value.length() > limit) throw new IllegalStateException("SEARCH_INVALID_" + field.toUpperCase());
        return value;
    }

    private static String limited(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); }
        catch (java.time.format.DateTimeParseException ignored) { return null; }
    }
}
