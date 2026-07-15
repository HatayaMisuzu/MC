package com.mccompanion.runtime.search;

import java.time.Duration;
import java.util.List;

public record SearchQuery(String query, List<String> allowedDomains, int maxResults,
                          Integer recencyDays, String locale, boolean safeSearch, Duration timeout) {
    public SearchQuery {
        query = SearchPrivacy.requireSafeQuery(query);
        allowedDomains = allowedDomains == null ? List.of() : allowedDomains.stream()
                .map(SearchSecurity::normalizedDomain).distinct().limit(16).toList();
        if (maxResults < 1 || maxResults > 10) throw new IllegalArgumentException("maxResults must be 1..10");
        if (recencyDays != null && (recencyDays < 1 || recencyDays > 3650)) {
            throw new IllegalArgumentException("recencyDays must be 1..3650");
        }
        locale = locale == null || locale.isBlank() ? "en" : locale.strip();
        if (!locale.matches("[A-Za-z]{2,3}([_-][A-Za-z]{2})?")) throw new IllegalArgumentException("locale is invalid");
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("search timeout must be at most 30 seconds");
        }
    }
}
