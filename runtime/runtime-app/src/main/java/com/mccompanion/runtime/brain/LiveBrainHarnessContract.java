package com.mccompanion.runtime.brain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Safe preflight contract for the three real-provider scenarios. No prompt or credential is emitted. */
public final class LiveBrainHarnessContract {
    public static final List<String> SCENARIOS = List.of(
            "basic-open-task", "unknown-mod-generic-task", "interruption-recovery");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private LiveBrainHarnessContract() { }

    public static ObjectNode preflight(Map<String, String> environment, Instant now) {
        return preflight(environment::get, now);
    }

    static ObjectNode preflight(Function<String, String> environment, Instant now) {
        boolean enabled = Boolean.parseBoolean(value(environment, "MCAC_LIVE_BRAIN_ENABLED", "false"));
        String mode = value(environment, "MCAC_LIVE_BRAIN_MODE", "openai-compatible").strip();
        String model = bounded(value(environment, "MCAC_LIVE_BRAIN_MODEL", "unspecified"), 128);
        String tokenEnv = value(environment, "MCAC_LIVE_BRAIN_TOKEN_ENV", "MC_COMPANION_BRAIN_TOKEN").strip();
        if (!ENV_NAME.matcher(tokenEnv).matches()) throw new IllegalArgumentException("invalid live Brain token environment name");
        LiveBrainBudget budget = new LiveBrainBudget(
                integer(environment, "MCAC_LIVE_BRAIN_MAX_REQUESTS", 24),
                integer(environment, "MCAC_LIVE_BRAIN_MAX_INPUT_TOKENS", 30_000),
                integer(environment, "MCAC_LIVE_BRAIN_MAX_OUTPUT_TOKENS", 8_000),
                java.time.Duration.ofMinutes(integer(environment, "MCAC_LIVE_BRAIN_MAX_WALL_CLOCK_MINUTES", 15)),
                integer(environment, "MCAC_LIVE_BRAIN_MAX_RETRIES", 2));
        String credential = environment.apply(tokenEnv);
        String status = !enabled ? "DISABLED_REAL_CALLS"
                : credential == null || credential.isBlank() ? "BLOCKED_BY_CREDENTIALS"
                : "READY_FOR_LIVE_WORLD";
        ObjectNode report = Json.object().put("schemaVersion", 1).put("liveModel", true)
                .put("status", status).put("providerType", bounded(mode, 32)).put("model", model)
                .put("credentialSource", "environment:" + tokenEnv).put("generatedAt", now.toString());
        var budgetNode = report.putObject("budget");
        budgetNode.put("maxRequests", budget.maxRequests()).put("maxInputTokens", budget.maxInputTokens())
                .put("maxOutputTokens", budget.maxOutputTokens())
                .put("maxWallClockMinutes", budget.maxWallClock().toMinutes())
                .put("maxRetries", budget.maxRetries());
        var scenarios = report.putArray("scenarios");
        SCENARIOS.forEach(id -> scenarios.addObject().put("id", id).put("status", "NOT_RUN"));
        var categories = report.putArray("allowedFailureCategories");
        for (LiveBrainFailureCategory category : LiveBrainFailureCategory.values()) categories.add(category.name());
        return report;
    }

    private static String value(Function<String, String> environment, String key, String fallback) {
        String value = environment.apply(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int integer(Function<String, String> environment, String key, int fallback) {
        try { return Integer.parseInt(value(environment, key, Integer.toString(fallback))); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(key + " must be an integer", invalid); }
    }

    private static String bounded(String value, int max) {
        String safe = value == null ? "" : value.strip();
        if (safe.length() > max) throw new IllegalArgumentException("live Brain metadata is too long");
        return safe;
    }
}
