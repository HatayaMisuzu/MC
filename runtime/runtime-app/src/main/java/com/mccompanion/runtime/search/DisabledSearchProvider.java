package com.mccompanion.runtime.search;

import java.util.List;

public final class DisabledSearchProvider implements SearchProvider {
    @Override public List<SearchSource> query(SearchQuery request) { throw new IllegalStateException("SEARCH_DISABLED"); }
    @Override public SearchPage open(SearchSource source, SearchQuery policy) { throw new IllegalStateException("SEARCH_DISABLED"); }
    @Override public boolean available() { return false; }
}
