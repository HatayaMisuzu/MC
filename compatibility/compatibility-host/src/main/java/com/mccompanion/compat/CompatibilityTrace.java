package com.mccompanion.compat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Aggregate-safe bounded trace of resolution and declarative capability dispatch. */
public final class CompatibilityTrace {
    private static final int CAPACITY = 256;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final Clock clock;

    public CompatibilityTrace() {
        this(Clock.systemUTC());
    }

    CompatibilityTrace(Clock clock) {
        this.clock = clock;
    }

    public synchronized Entry record(String instanceId, String capabilityId, String sourcePack,
                                     String result, Map<String, String> evidence) {
        if (entries.size() >= CAPACITY) entries.removeFirst();
        Entry entry = new Entry(UUID.randomUUID().toString(), clock.instant(), bounded(instanceId, 128),
                bounded(capabilityId, 128), bounded(sourcePack, 160), bounded(result, 64),
                Map.copyOf(evidence == null ? Map.of() : evidence));
        if (entry.evidence().size() > 16 || entry.evidence().entrySet().stream().anyMatch(value ->
                value.getKey().length() > 64 || value.getValue().length() > 256)) {
            throw new IllegalArgumentException("TRACE_EVIDENCE_LIMIT");
        }
        entries.addLast(entry);
        return entry;
    }

    public synchronized List<Entry> recent(int limit) {
        int bounded = Math.max(1, Math.min(100, limit));
        return entries.stream().skip(Math.max(0, entries.size() - bounded)).toList();
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException("TRACE_FIELD_INVALID");
        }
        return value;
    }

    public record Entry(String traceId, Instant at, String instanceId, String capabilityId,
                        String sourcePack, String result, Map<String, String> evidence) {}
}
