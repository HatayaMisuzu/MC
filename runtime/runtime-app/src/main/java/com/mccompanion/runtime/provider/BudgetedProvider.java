package com.mccompanion.runtime.provider;

import com.mccompanion.runtime.agent.AgentDecision;
import com.mccompanion.runtime.intent.Intent;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;

/** Enforces concurrency, call-rate, bounded retries, and interruption cancellation. */
public final class BudgetedProvider implements IntentProvider, DecisionProvider {
    private final IntentProvider intents;
    private final DecisionProvider decisions;
    private final Semaphore concurrent;
    private final int callsPerMinute;
    private final int retries;
    private final Clock clock;
    private final ArrayDeque<Long> calls = new ArrayDeque<>();

    public BudgetedProvider(OpenAiCompatibleProvider provider, int maxConcurrent, int callsPerMinute, int retries) {
        this(provider, provider, maxConcurrent, callsPerMinute, retries, Clock.systemUTC());
    }

    BudgetedProvider(IntentProvider intents, DecisionProvider decisions, int maxConcurrent,
                     int callsPerMinute, int retries, Clock clock) {
        if (maxConcurrent < 1 || callsPerMinute < 1 || retries < 0 || retries > 3) throw new IllegalArgumentException("Invalid provider budget");
        this.intents = intents; this.decisions = decisions; this.concurrent = new Semaphore(maxConcurrent, true);
        this.callsPerMinute = callsPerMinute; this.retries = retries; this.clock = clock;
    }

    @Override public Intent parse(String userText) throws ProviderException { return invoke(() -> intents.parse(userText)); }
    @Override public AgentDecision decide(AgentRequest request) throws ProviderException { return invoke(() -> decisions.decide(request)); }

    private <T> T invoke(Call<T> call) throws ProviderException {
        if (Thread.currentThread().isInterrupted()) throw new ProviderException("PROVIDER_CANCELLED", "Provider request was cancelled");
        if (!concurrent.tryAcquire()) throw new ProviderException("PROVIDER_BUSY", "Provider concurrency budget is exhausted");
        try {
            ProviderException last = null;
            for (int attempt = 0; attempt <= retries; attempt++) {
                reserveCall();
                try { return call.run(); }
                catch (ProviderException failure) {
                    last = failure;
                    if (!failure.code().equals("PROVIDER_RETRYABLE") || attempt == retries) throw failure;
                    if (Thread.currentThread().isInterrupted()) throw new ProviderException("PROVIDER_CANCELLED", "Provider request was cancelled");
                }
            }
            throw last;
        } finally { concurrent.release(); }
    }

    private synchronized void reserveCall() throws ProviderException {
        long cutoff = clock.millis() - 60_000;
        while (!calls.isEmpty() && calls.peekFirst() <= cutoff) calls.removeFirst();
        if (calls.size() >= callsPerMinute) throw new ProviderException("PROVIDER_BUDGET_EXCEEDED", "Provider call budget is exhausted");
        calls.addLast(clock.millis());
    }

    @Override public void close() { intents.close(); }
    @FunctionalInterface private interface Call<T> { T run() throws ProviderException; }
}
