package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BrainReconnectE2ETest {
    @Test void boundedBackoffEntersSafeIdleAndRecoversSameSession() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger safeIdle = new AtomicInteger();
        AtomicInteger recovered = new AtomicInteger();
        ExternalBrainAdapter provider = provider(calls, 2);
        BrainReconnectListener listener = new BrainReconnectListener() {
            @Override public void safeIdle(BrainSession session, int attempt, Duration delay,
                                           LiveBrainFailureCategory category) {
                assertEquals("session-1", session.sessionId());
                assertEquals(LiveBrainFailureCategory.NETWORK, category);
                assertTrue(delay.toMillis() >= 250);
                safeIdle.incrementAndGet();
            }
            @Override public void recovered(BrainSession session, int attempts) {
                assertEquals("session-1", session.sessionId());
                assertEquals(2, attempts);
                recovered.incrementAndGet();
            }
        };
        try (var adapter = new BudgetedExternalBrainAdapter(provider,
                new LiveBrainBudget(8, 8_000, 1_000, Duration.ofMinutes(1), 2), listener)) {
            BrainSession session = adapter.openSession(request("c1"));
            BrainTurnResult result = adapter.continueTurn(turn(session));
            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
            assertEquals(3, calls.get());
            assertEquals(2, safeIdle.get());
            assertEquals(1, recovered.get());
            assertEquals(2, adapter.usage().retries());
        }
    }

    @Test void userCancelInterruptsBackoffAndDoesNotCancelAnotherSession() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        ExternalBrainAdapter provider = new ExternalBrainAdapter() {
            @Override public BrainSession openSession(BrainSessionRequest request) {
                return new BrainSession("session-" + sequence.incrementAndGet(), request.controllerId(),
                        request.companionId(), Instant.now());
            }
            @Override public BrainTurnResult continueTurn(BrainTurnRequest request) {
                if (request.sessionId().equals("session-1")) throw new IllegalStateException("BRAIN_IO_ERROR");
                return BrainTurnResult.finalResponse("other session remains active");
            }
            @Override public void cancel(String sessionId, String reason) { }
            @Override public BrainHealth health() { return new BrainHealth("OK", "fixture", "safe", Instant.now()); }
        };
        BrainReconnectListener listener = new BrainReconnectListener() {
            @Override public void safeIdle(BrainSession session, int attempt, Duration delay,
                                           LiveBrainFailureCategory category) { waiting.countDown(); }
        };
        try (var adapter = new BudgetedExternalBrainAdapter(provider,
                new LiveBrainBudget(10, 8_000, 1_000, Duration.ofMinutes(1), 5), listener);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            BrainSession first = adapter.openSession(request("c1"));
            BrainSession second = adapter.openSession(request("c2"));
            var failed = executor.submit(() -> adapter.continueTurn(turn(first)));
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            adapter.cancel(first.sessionId(), "USER_CANCELLED");
            var exception = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> failed.get(2, TimeUnit.SECONDS));
            assertInstanceOf(LiveBrainBudgetException.class, exception.getCause());
            assertEquals(LiveBrainFailureCategory.USER_CANCELLED,
                    ((LiveBrainBudgetException) exception.getCause()).category());
            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, adapter.continueTurn(turn(second)).kind());
        }
    }

    private static ExternalBrainAdapter provider(AtomicInteger calls, int failures) {
        return new ExternalBrainAdapter() {
            @Override public BrainSession openSession(BrainSessionRequest request) {
                return new BrainSession("session-1", request.controllerId(), request.companionId(), Instant.now());
            }
            @Override public BrainTurnResult continueTurn(BrainTurnRequest request) {
                if (calls.getAndIncrement() < failures) throw new IllegalStateException("BRAIN_IO_ERROR");
                return BrainTurnResult.finalResponse("recovered");
            }
            @Override public void cancel(String sessionId, String reason) { }
            @Override public BrainHealth health() { return new BrainHealth("OK", "fixture", "safe", Instant.now()); }
        };
    }

    private static BrainSessionRequest request(String companion) {
        return new BrainSessionRequest("controller", companion, AgentContext.empty(companion, List.of()), List.of());
    }

    private static BrainTurnRequest turn(BrainSession session) {
        return new BrainTurnRequest(session.sessionId(), "continue", AgentContext.empty(session.companionId(), List.of()),
                List.of(), 4);
    }
}
