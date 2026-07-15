package com.mccompanion.runtime.search;

import java.util.regex.Pattern;

final class SearchPrivacy {
    private static final Pattern SECRET = Pattern.compile("(?i)(sk-[a-z0-9_-]{12,}|bearer\\s+[a-z0-9._-]{12,}|api[_ -]?key\\s*[:=])");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
    private static final Pattern PATH = Pattern.compile("(?i)([a-z]:\\\\|/(users|home|var/log)/|\\\\\\\\[^\\s]+\\\\)");
    private static final Pattern ADDRESS = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{2,5})?\\b");
    private static final Pattern COORDINATES = Pattern.compile("(?i)\\b[xyz]\\s*[:=]\\s*-?\\d+.{0,16}[xyz]\\s*[:=]\\s*-?\\d+");

    private SearchPrivacy() { }

    static String requireSafeQuery(String value) {
        if (value == null || value.isBlank() || value.length() > 512) throw new IllegalArgumentException("query must be 1..512 characters");
        String query = value.strip();
        if (SECRET.matcher(query).find() || UUID.matcher(query).find() || PATH.matcher(query).find()
                || ADDRESS.matcher(query).find() || COORDINATES.matcher(query).find()) {
            throw new IllegalArgumentException("SEARCH_PRIVACY_REJECTED");
        }
        return query;
    }
}
