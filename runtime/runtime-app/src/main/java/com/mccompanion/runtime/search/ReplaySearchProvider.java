package com.mccompanion.runtime.search;

import java.util.List;
import java.util.Map;

/** Deterministic non-Live provider for automation. */
public final class ReplaySearchProvider implements SearchProvider {
    private final List<SearchSource> sources;
    private final Map<String, SearchPage> pages;

    public ReplaySearchProvider(List<SearchSource> sources, Map<String, SearchPage> pages) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.pages = pages == null ? Map.of() : Map.copyOf(pages);
    }

    @Override public List<SearchSource> query(SearchQuery request) {
        return sources.stream().filter(source -> request.allowedDomains().isEmpty()
                        || request.allowedDomains().contains(source.domain()))
                .limit(request.maxResults()).toList();
    }

    @Override public SearchPage open(SearchSource source, SearchQuery policy) {
        SearchPage page = pages.get(source.sourceId());
        if (page == null) throw new IllegalStateException("SEARCH_SOURCE_NOT_FOUND");
        return page;
    }
}
