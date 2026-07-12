package com.mccompanion.runtime.logging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

public final class Redactor {
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+\\-/=]+");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(api[_-]?key|token|authorization|secret)(\\s*[:=]\\s*)([^,\\s}]+)");
    private final List<String> knownSecrets = new CopyOnWriteArrayList<>();

    public void registerSecret(String secret) {
        if (secret != null && secret.length() >= 4) {
            knownSecrets.add(secret);
        }
    }

    public String redact(String message) {
        String result = message == null ? "" : message;
        result = BEARER.matcher(result).replaceAll("$1[REDACTED]");
        result = SECRET_FIELD.matcher(result).replaceAll("$1$2[REDACTED]");
        for (String secret : knownSecrets) {
            result = result.replace(secret, "[REDACTED]");
        }
        return result;
    }
}
