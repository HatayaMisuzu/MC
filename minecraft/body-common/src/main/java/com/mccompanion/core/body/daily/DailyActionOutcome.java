package com.mccompanion.core.body.daily;

import java.util.HashMap;
import java.util.Map;

/** Shared terminal-status projection used by both Loader bridges. */
public final class DailyActionOutcome {
    private DailyActionOutcome() { }

    public static RuntimeObservation forRuntime(DailyActionEngine.Observation observation) {
        if (observation == null) throw new IllegalArgumentException("observation is required");
        Map<String, String> details = new HashMap<>(observation.details());
        details.put("dailyStatus", observation.status().name());
        String code = observation.code();
        if (observation.status() == DailyActionEngine.Status.UNCERTAIN) {
            details.put("dailyCode", code);
            code = "UNCERTAIN_EFFECT";
        }
        return new RuntimeObservation(code, details);
    }

    public record RuntimeObservation(String code, Map<String, String> details) {
        public RuntimeObservation {
            code = code == null ? "" : code;
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }
}
