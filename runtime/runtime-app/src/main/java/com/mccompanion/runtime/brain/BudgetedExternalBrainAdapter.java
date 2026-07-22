package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.json.Json;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final Instant startedAt;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private int requests;
    private int retries;
    private int inputTokens;
    private int outputTokens;

    public BudgetedExternalBrainAdapter(ExternalBrainAdapter delegate, LiveBrainBudget budget) {
        this(delegate, budget, Clock.systemUTC());
    }

    BudgetedExternalBrainAdapter(ExternalBrainAdapter delegate, LiveBrainBudget budget, Clock clock) {
        if (delegate == null) throw new IllegalArgumentException("delegate is required");
        if (budget == null) throw new IllegalArgumentException("budget is required");
        this.delegate = delegate;
        this.budget = budget;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    @Override public synchronized BrainSession openSession(BrainSessionRequest request) {
        beforeRequest(estimate(request));
        BrainSession result = delegate.openSession(request);
        accountOutput(result);
        return result;
    }

    @Override public boolean supportsResume() { return delegate.supportsResume(); }

    @Override public synchronized BrainSession resumeSession(BrainSessionRequest request, String sessionId) {
        beforeRequest(estimate(request));
        BrainSession result = delegate.resumeSession(request, sessionId);
        accountOutput(result);
        return result;
    }

    @Override public synchronized BrainTurnResult continueTurn(BrainTurnRequest request) {
        int attempt = 0;
        while (true) {
            beforeRequest(estimate(request));
            try {
                BrainTurnResult result = delegate.continueTurn(request);
                accountOutput(result);
                return result;
            } catch (RuntimeException failure) {
                if (cancelled.get()) throw new LiveBrainBudgetException("BRAIN_USER_CANCELLED",
                        LiveBrainFailureCategory.USER_CANCELLED);
                if (attempt >= budget.maxRetries() || !retriable(failure)) throw failure;
                attempt++;
                retries++;
            }
        }
    }

    @Override public void cancel(String sessionId, String reason) {
        cancelled.set(true);
        delegate.cancel(sessionId, reason);
    }

    @Override public BrainHealth health() { return delegate.health(); }

    public synchronized LiveBrainUsage usage() {
        return new LiveBrainUsage(requests, retries, inputTokens, outputTokens, "ESTIMATED",
                Duration.between(startedAt, clock.instant()).toMillis());
    }

    @Override public void close() { cancelled.set(true); delegate.close(); }

    private void beforeRequest(int estimatedInput) {
        if (cancelled.get()) throw new LiveBrainBudgetException("BRAIN_USER_CANCELLED",
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

    private void accountOutput(Object result) {
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
}
