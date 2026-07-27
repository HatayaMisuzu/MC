package com.mccompanion.runtime.search;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

interface SearchHttpTransport extends AutoCloseable {
    record Response(int status, Map<String, String> headers, byte[] body) {
        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
    }

    Response send(SearchSecurity.ResolvedTarget target, String method, Map<String, String> requestHeaders,
                  byte[] body, Duration timeout, int maximumBytes) throws IOException;

    @Override default void close() { }
}
