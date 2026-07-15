package com.mccompanion.runtime.search;

import java.util.List;

public interface SearchProvider extends AutoCloseable {
    List<SearchSource> query(SearchQuery request);
    SearchPage open(SearchSource source, SearchQuery policy);
    default void cancel(String searchId) { }
    default boolean available() { return true; }
    @Override default void close() { }
}
