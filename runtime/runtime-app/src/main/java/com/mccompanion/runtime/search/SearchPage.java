package com.mccompanion.runtime.search;

import java.time.Instant;

public record SearchPage(String sourceId, String title, String url, String domain, String content,
                         String contentType, boolean promptInjectionFlagged, Instant retrievedAt) { }
