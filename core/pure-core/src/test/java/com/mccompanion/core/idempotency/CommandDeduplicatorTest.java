package com.mccompanion.core.idempotency;

import com.mccompanion.core.id.CommandId;
import com.mccompanion.core.testutil.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandDeduplicatorTest {
    private static final Instant START = Instant.parse("2026-07-12T00:00:00Z");

    @Test
    void duplicateCommandReturnsOriginalResultWithoutRepeatingSideEffect() {
        MutableClock clock = new MutableClock(START);
        CommandDeduplicator<String> deduplicator = new CommandDeduplicator<>(clock,
                Duration.ofMinutes(1), 10);
        CommandId id = CommandId.random();
        CommandFingerprint fingerprint = fingerprint("follow");
        AtomicInteger executions = new AtomicInteger();

        IdempotencyResult<String> first = deduplicator.execute(id, fingerprint,
                () -> "result-" + executions.incrementAndGet());
        IdempotencyResult<String> duplicate = deduplicator.execute(id, fingerprint,
                () -> "result-" + executions.incrementAndGet());

        assertFalse(first.duplicate());
        assertTrue(duplicate.duplicate());
        assertEquals(first.value(), duplicate.value());
        assertEquals(1, executions.get());
    }

    @Test
    void commandIdReuseWithDifferentContentIsRejected() {
        CommandDeduplicator<String> deduplicator = new CommandDeduplicator<>(new MutableClock(START),
                Duration.ofMinutes(1), 10);
        CommandId id = CommandId.random();
        deduplicator.execute(id, fingerprint("follow"), () -> "ok");

        assertThrows(IdempotencyConflictException.class,
                () -> deduplicator.execute(id, fingerprint("stop"), () -> "unsafe"));
    }

    @Test
    void expiryAndCapacityPermitBoundedReexecution() {
        MutableClock clock = new MutableClock(START);
        CommandDeduplicator<String> deduplicator = new CommandDeduplicator<>(clock,
                Duration.ofSeconds(10), 1);
        CommandId firstId = CommandId.random();
        AtomicInteger executions = new AtomicInteger();
        deduplicator.execute(firstId, fingerprint("one"), () -> "v" + executions.incrementAndGet());
        deduplicator.execute(CommandId.random(), fingerprint("two"), () -> "v" + executions.incrementAndGet());
        assertEquals(1, deduplicator.size());

        deduplicator.execute(firstId, fingerprint("one"), () -> "v" + executions.incrementAndGet());
        assertEquals(3, executions.get(), "capacity eviction permits deliberate re-execution");

        clock.advance(Duration.ofSeconds(10));
        assertEquals(0, deduplicator.size());
    }

    @Test
    void failedOperationIsNotCached() {
        CommandDeduplicator<String> deduplicator = new CommandDeduplicator<>(new MutableClock(START),
                Duration.ofMinutes(1), 10);
        CommandId id = CommandId.random();
        AtomicInteger executions = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> deduplicator.execute(id, fingerprint("one"), () -> {
            executions.incrementAndGet();
            throw new IllegalStateException("failed");
        }));

        assertEquals("ok", deduplicator.execute(id, fingerprint("one"), () -> {
            executions.incrementAndGet();
            return "ok";
        }).value());
        assertEquals(2, executions.get());
    }

    private static CommandFingerprint fingerprint(String value) {
        return CommandFingerprint.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
