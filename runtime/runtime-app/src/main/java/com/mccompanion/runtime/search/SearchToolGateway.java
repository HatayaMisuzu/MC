package com.mccompanion.runtime.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Session-bound search tools. URLs are never accepted from a Brain tool call. */
public final class SearchToolGateway implements ToolGateway, AutoCloseable {
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final long CACHE_TTL_NANOS = Duration.ofMinutes(5).toNanos();
    private final SearchProvider provider;
    private final List<String> globallyAllowedDomains;
    private final List<String> deniedDomains;
    private final Map<String, SearchState> states = new ConcurrentHashMap<>();
    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public SearchToolGateway(SearchProvider provider) { this(provider, List.of(), List.of()); }

    public SearchToolGateway(SearchProvider provider, List<String> globallyAllowedDomains, List<String> deniedDomains) {
        this.provider = java.util.Objects.requireNonNull(provider);
        this.globallyAllowedDomains = globallyAllowedDomains == null ? List.of()
                : globallyAllowedDomains.stream().map(SearchSecurity::normalizedDomain).distinct().toList();
        this.deniedDomains = deniedDomains == null ? List.of()
                : deniedDomains.stream().map(SearchSecurity::normalizedDomain).distinct().toList();
    }

    @Override public List<ToolDefinition> definitions(ToolContext context) {
        if (!provider.available()) return List.of();
        return List.of(definition("search.query", "Search through the configured privacy boundary", querySchema(), false),
                definition("search.open", "Open a source returned by search.query using its source id", sourceSchema(), true),
                definition("search.citations", "List citations from the current search session", Json.object(), true),
                definition("search.cancel", "Cancel and clear the current search session", Json.object(), true));
    }

    @Override public ToolResult execute(ToolContext context, ToolCall call) {
        if (!provider.available()) return ToolResult.rejected(call, "SEARCH_DISABLED", "Search is disabled");
        try {
            return switch (call.name()) {
                case "search.query" -> query(context, call);
                case "search.open" -> open(context, call);
                case "search.citations" -> citations(context, call);
                case "search.cancel" -> cancel(context, call);
                default -> ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Search tool is unavailable");
            };
        } catch (IllegalArgumentException failure) {
            return ToolResult.rejected(call, failure.getMessage().startsWith("SEARCH_") ? failure.getMessage()
                    : "INVALID_TOOL_ARGUMENTS", "Search request was rejected by policy");
        } catch (IllegalStateException failure) {
            return ToolResult.rejected(call, failure.getMessage() == null ? "SEARCH_FAILED" : failure.getMessage(),
                    "Search provider did not complete safely");
        }
    }

    private ToolResult query(ToolContext context, ToolCall call) {
        JsonNode a = call.arguments();
        rejectUnexpected(a, List.of("query", "allowedDomains", "maxResults", "recencyDays", "locale", "safeSearch", "timeoutSeconds"));
        List<String> domains = effectiveDomains(strings(a.path("allowedDomains")));
        SearchQuery request = new SearchQuery(a.path("query").asText(""), domains, a.path("maxResults").asInt(5),
                a.has("recencyDays") ? a.path("recencyDays").asInt() : null, a.path("locale").asText("en"),
                a.path("safeSearch").asBoolean(true), Duration.ofSeconds(a.path("timeoutSeconds").asInt(15)));
        CacheKey cacheKey = new CacheKey(context.companionId(), request);
        long now = System.nanoTime();
        CacheEntry cached = cache.get(cacheKey);
        boolean cacheHit = cached != null && now - cached.storedAtNanos() <= CACHE_TTL_NANOS;
        if (cached != null && !cacheHit) cache.remove(cacheKey, cached);
        List<SearchSource> sources;
        if (cacheHit) {
            sources = cached.sources();
        } else {
            sources = provider.query(request).stream()
                    .filter(source -> deniedDomains.stream().noneMatch(denied -> source.domain().equals(denied)
                            || source.domain().endsWith("." + denied)))
                    .limit(request.maxResults()).toList();
            evictCache(now);
            cache.put(cacheKey, new CacheEntry(now, sources));
        }
        String searchId = UUID.randomUUID().toString();
        states.put(context.brainSessionId(), new SearchState(searchId, request, sources, cacheKey));
        ObjectNode observation = Json.object().put("searchId", searchId)
                .put("trustBoundary", "UNTRUSTED_EXTERNAL_CONTENT").put("cacheHit", cacheHit);
        observation.set("sources", Json.MAPPER.valueToTree(sources));
        return new ToolResult(call.callId(), call.name(), true, "OK", observation, true);
    }

    private List<String> effectiveDomains(List<String> requested) {
        List<String> normalized = requested.stream().map(SearchSecurity::normalizedDomain).toList();
        List<String> selected = globallyAllowedDomains.isEmpty() ? normalized
                : normalized.isEmpty() ? globallyAllowedDomains
                : normalized.stream().filter(value -> globallyAllowedDomains.stream()
                        .anyMatch(global -> value.equals(global) || value.endsWith("." + global))).toList();
        if (!normalized.isEmpty() && selected.isEmpty() && !globallyAllowedDomains.isEmpty()) {
            throw new IllegalArgumentException("SEARCH_DOMAIN_DENIED");
        }
        if (selected.stream().anyMatch(value -> deniedDomains.stream()
                .anyMatch(denied -> value.equals(denied) || value.endsWith("." + denied)))) {
            throw new IllegalArgumentException("SEARCH_DOMAIN_DENIED");
        }
        return selected;
    }

    private ToolResult open(ToolContext context, ToolCall call) {
        rejectUnexpected(call.arguments(), List.of("sourceId"));
        SearchState state = requiredState(context);
        String sourceId = call.arguments().path("sourceId").asText("");
        SearchSource source = state.sources().stream().filter(value -> value.sourceId().equals(sourceId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("SEARCH_SOURCE_NOT_FOUND"));
        SearchPage page = provider.open(source, state.policy());
        return new ToolResult(call.callId(), call.name(), true,
                page.promptInjectionFlagged() ? "UNTRUSTED_PROMPT_INJECTION_FLAGGED" : "OK",
                Json.MAPPER.valueToTree(page), true);
    }

    private ToolResult citations(ToolContext context, ToolCall call) {
        rejectUnexpected(call.arguments(), List.of());
        SearchState state = requiredState(context);
        ObjectNode result = Json.object().put("searchId", state.searchId());
        result.set("sources", Json.MAPPER.valueToTree(state.sources()));
        return new ToolResult(call.callId(), call.name(), true, "OK", result, true);
    }

    private ToolResult cancel(ToolContext context, ToolCall call) {
        rejectUnexpected(call.arguments(), List.of());
        SearchState state = states.remove(context.brainSessionId());
        if (state != null) {
            cache.remove(state.cacheKey());
            provider.cancel(state.searchId());
        }
        return new ToolResult(call.callId(), call.name(), true, "SEARCH_CANCELLED", Json.object(), true);
    }

    private SearchState requiredState(ToolContext context) {
        SearchState state = states.get(context.brainSessionId());
        if (state == null) throw new IllegalStateException("SEARCH_SESSION_NOT_FOUND");
        return state;
    }

    private void evictCache(long now) {
        cache.entrySet().removeIf(entry -> now - entry.getValue().storedAtNanos() > CACHE_TTL_NANOS);
        if (cache.size() < MAX_CACHE_ENTRIES) return;
        cache.entrySet().stream().min(java.util.Comparator.comparingLong(entry -> entry.getValue().storedAtNanos()))
                .ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
    }

    @Override public void close() { states.clear(); cache.clear(); provider.close(); }

    private static ToolDefinition definition(String name, String description, JsonNode properties, boolean idempotent) {
        ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
        schema.set("properties", properties);
        if (name.equals("search.query")) schema.putArray("required").add("query");
        if (name.equals("search.open")) schema.putArray("required").add("sourceId");
        return new ToolDefinition(name, "1.0", description, schema, "MEDIUM", "SEARCH_WEB",
                Duration.ofSeconds(30), idempotent);
    }

    private static ObjectNode querySchema() {
        ObjectNode p = Json.object();
        p.putObject("query").put("type", "string").put("maxLength", 512);
        p.putObject("allowedDomains").put("type", "array").putObject("items").put("type", "string");
        p.putObject("maxResults").put("type", "integer").put("minimum", 1).put("maximum", 10);
        p.putObject("recencyDays").put("type", "integer").put("minimum", 1).put("maximum", 3650);
        p.putObject("locale").put("type", "string"); p.putObject("safeSearch").put("type", "boolean");
        p.putObject("timeoutSeconds").put("type", "integer").put("minimum", 1).put("maximum", 30);
        return p;
    }
    private static ObjectNode sourceSchema() { return Json.object().set("sourceId", Json.object().put("type", "string")); }
    private static List<String> strings(JsonNode node) {
        if (node.isMissingNode()) return List.of();
        if (!node.isArray() || node.size() > 16) throw new IllegalArgumentException("allowedDomains must be an array");
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        node.forEach(value -> { if (!value.isTextual()) throw new IllegalArgumentException("allowedDomains must contain strings"); values.add(value.asText()); });
        return List.copyOf(values);
    }
    private static void rejectUnexpected(JsonNode node, List<String> allowed) {
        if (!node.isObject()) throw new IllegalArgumentException("arguments must be an object");
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw new IllegalArgumentException("unexpected argument"); });
    }
    private record SearchState(String searchId, SearchQuery policy, List<SearchSource> sources, CacheKey cacheKey) {
        private SearchState { sources = List.copyOf(sources); }
    }
    private record CacheKey(String companionId, SearchQuery policy) { }
    private record CacheEntry(long storedAtNanos, List<SearchSource> sources) {
        private CacheEntry { sources = List.copyOf(sources); }
    }
}
