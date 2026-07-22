package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.json.Json;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Enforces aggregate live-call budgets around an existing provider adapter. This layer does not
 * choose goals or tools. Token counts are conservative estimates unless a future adapter reports
 * provider usage explicitly, and that distinction is exposed in {@link #usage()}.
 */
public final class BudgetedExternalBrainAdapter implements ExternalBrainAdapter {
    private static final Set<String> RETRIABLE_CODES = Set.of(
            "BRAIN_IO_ERROR", "BRAIN_HTTP_429", "BRAIN_HTTP_502", "BRAIN_HTTP_503", "BRAIN_HTTP_504");
    private final ExternalBrainAdapter delegate;
    private final LiveBrainBudget budget;
    private final Clock clock;
    private final BrainReconnectListener reconnectListener;
    private final Instant startedAt;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<String> cancelledSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, BrainSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Thread> activeThreads = new ConcurrentHashMap<>();
    private int requests;
    private int retries;
    private int inputTokens;
    private int outputTokens;

    public BudgetedExternalBrainAdapter(ExternalBrainAdapter delegate, LiveBrainBudget budget) {
        this(delegate, budget, Clock.systemUTC(), BrainReconnectListener.NONE);
    }

    BudgetedExternalBrainAdapter(ExternalBrainAdapter delegate, LiveBrainBudget budget, Clock clock) {
        this(delegate, budget, clock, BrainReconnectListener.NONE);
    }

    public BudgetedExternalBrainAdapter(ExternalBrainAdapter delegate, LiveBrainBudget budget,
                                        BrainReconnectListener reconnectListener) {
        this(delegate, budget, Clock.systemUTC(), reconnectListener);
    }

    BudgetedExternalBrainAdapter(ExternalBrainAdapter delegate, LiveBrainBudget budget, Clock clock,
                                 BrainReconnectListener reconnectListener) {
        if (delegate == null) throw new IllegalArgumentException("delegate is required");
        if (budget == null) throw new IllegalArgumentException("budget is required");
        this.delegate = delegate;
        this.budget = budget;
        this.clock = clock;
        this.reconnectListener = reconnectListener == null ? BrainReconnectListener.NONE : reconnectListener;
        this.startedAt = clock.instant();
    }

    @Override public synchronized BrainSession openSession(BrainSessionRequest request) {
        beforeRequest(estimate(request));
        BrainSession result = delegate.openSession(request);
        accountOutput(result);
        sessions.put(result.sessionId(), result);
        return result;
    }

    @Override public boolean supportsResume() { return delegate.supportsResume(); }

    @Override public synchronized BrainSession resumeSession(BrainSessionRequest request, String sessionId) {
        beforeRequest(estimate(request));
        BrainSession result = delegate.resumeSession(request, sessionId);
        accountOutput(result);
        sessions.put(result.sessionId(), result);
        return result;
    }

    @Override public BrainTurnResult continueTurn(BrainTurnRequest request) {
        int attempt = 0;
        activeThreads.put(request.sessionId(), Thread.currentThread());
        try {
            while (true) {
                if (cancelledSessions.contains(request.sessionId())) throw cancelled();
                beforeRequest(estimate(request));
                try {
                    BrainTurnResult result = delegate.continueTurn(request);
                    accountOutput(result);
                    if (attempt > 0) reconnectListener.recovered(sessions.get(request.sessionId()), attempt);
                    return result;
                } catch (RuntimeException failure) {
                    if (cancelledSessions.contains(request.sessionId()) || closed.get()
                            || Thread.currentThread().isInterrupted()) throw cancelled();
                    if (attempt >= budget.maxRetries() || !retriable(failure)) throw failure;
                    attempt++;
                    retries++;
                    Duration delay = retryDelay(attempt);
                    reconnectListener.safeIdle(sessions.get(request.sessionId()), attempt, delay, classify(failure));
                    waitForRetry(delay);
                }
            }
        } finally {
            activeThreads.remove(request.sessionId(), Thread.currentThread());
        }
    }

    @Override public void cancel(String sessionId, String reason) {
        cancelledSessions.add(sessionId);
        sessions.remove(sessionId);
        Thread active = activeThreads.get(sessionId);
        if (active != null) active.interrupt();
        delegate.cancel(sessionId, reason);
    }

    @Override public BrainHealth health() { return delegate.health(); }

    public synchronized LiveBrainUsage usage() {
        return new LiveBrainUsage(requests, retries, inputTokens, outputTokens, "ESTIMATED",
                Duration.between(startedAt, clock.instant()).toMillis());
    }

    @Override public void close() {
        closed.set(true);
        activeThreads.values().forEach(Thread::interrupt);
        activeThreads.clear();
        sessions.clear();
        delegate.close();
    }

    private synchronized void beforeRequest(int estimatedInput) {
        if (closed.get()) throw new LiveBrainBudgetException("BRAIN_USER_CANCELLED",
                LiveBrainFailureCategory.USER_CANCELLED);
        if (Duration.between(startedAt, clock.instant()).compareTo(budget.maxWallClock()) > 0) {
            throw new LiveBrainBudgetException("BRAIN_WALL_CLOCK_BUDGET_EXCEEDED", LiveBrainFailureCategory.TIMEOUT);
        }
        if (requests >= budget.maxRequests()) throw new LiveBrainBudgetException(
                "BRAIN_REQUEST_BUDGET_EXCEEDED", LiveBrainFailureCategory.RATE_LIMIT);
        if ((long) inputTokens + estimatedInput > budget.maxInputTokens()) throw new LiveBrainBudgetException(
                "BRAIN_INPUT_TOKEN_BUDGET_EXCEEDED", LiveBrainFailureCategory.RATE_LIMIT);
        requests++;
        inputTokens += estimatedInput;
    }

    private synchronized void accountOutput(Object result) {
        int estimated = estimate(result);
        if ((long) outputTokens + estimated > budget.maxOutputTokens()) throw new LiveBrainBudgetException(
                "BRAIN_OUTPUT_TOKEN_BUDGET_EXCEEDED", LiveBrainFailureCategory.RATE_LIMIT);
        outputTokens += estimated;
    }

    private static int estimate(Object value) {
        if (value == null) return 0;
        int chars = Json.write(Json.MAPPER.valueToTree(value)).length();
        return Math.max(1, (chars + 3) / 4);
    }

    private static boolean retriable(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && RETRIABLE_CODES.contains(message);
    }

    private static LiveBrainFailureCategory classify(RuntimeException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.equals("BRAIN_HTTP_429")) return LiveBrainFailureCategory.RATE_LIMIT;
        if (message.contains("TIMEOUT") || message.equals("BRAIN_HTTP_504")) return LiveBrainFailureCategory.TIMEOUT;
        return LiveBrainFailureCategory.NETWORK;
    }

    private static Duration retryDelay(int attempt) {
        long base = Math.min(5_000L, 250L << Math.min(4, attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 4L + 1L));
        return Duration.ofMillis(base + jitter);
    }

    private void waitForRetry(Duration delay) {
        try { Thread.sleep(delay.toMillis()); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cancelled();
        }
    }

    private static LiveBrainBudgetException cancelled() {
        return new LiveBrainBudgetException("BRAIN_USER_CANCELLED", LiveBrainFailureCategory.USER_CANCELLED);
    }
}
