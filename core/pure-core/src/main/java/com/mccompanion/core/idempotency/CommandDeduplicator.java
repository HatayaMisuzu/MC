package com.mccompanion.core.idempotency;

import com.mccompanion.core.id.CommandId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class CommandDeduplicator<R> {
    private final Clock clock;
    private final Duration retention;
    private final int maxEntries;
    private final LinkedHashMap<CommandId, Entry<R>> entries = new LinkedHashMap<>();

    public CommandDeduplicator(Clock clock, Duration retention, int maxEntries) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    public synchronized IdempotencyResult<R> execute(
            CommandId commandId,
            CommandFingerprint fingerprint,
            Supplier<? extends R> operation) {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(operation, "operation");
        Instant now = clock.instant();
        evictExpired(now);

        Entry<R> existing = entries.get(commandId);
        if (existing != null) {
            if (!existing.fingerprint.equals(fingerprint)) {
                throw new IdempotencyConflictException(commandId);
            }
            return new IdempotencyResult<>(existing.value, true);
        }

        R value = Objects.requireNonNull(operation.get(), "operation result");
        while (entries.size() >= maxEntries) {
            Iterator<Map.Entry<CommandId, Entry<R>>> iterator = entries.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
        entries.put(commandId, new Entry<>(fingerprint, value, now.plus(retention)));
        return new IdempotencyResult<>(value, false);
    }

    public synchronized int size() {
        evictExpired(clock.instant());
        return entries.size();
    }

    public int maxEntries() {
        return maxEntries;
    }

    private void evictExpired(Instant now) {
        entries.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
    }

    private record Entry<R>(CommandFingerprint fingerprint, R value, Instant expiresAt) {
    }
}
