package com.mccompanion.runtime.search;

import java.time.Instant;

public record SearchSource(String sourceId, String title, String url, String domain, String publisher,
                           Instant publishedAt, Instant retrievedAt, String snippet,
                           String trustLevel, String contentType) { }
