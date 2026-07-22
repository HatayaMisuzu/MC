package com.mccompanion.runtime.brain;

/** Bounded aggregate only. It never contains prompts, responses, credentials, or world data. */
public record LiveBrainUsage(int requests, int retries, int inputTokens, int outputTokens,
                             String tokenAccounting, long elapsedMillis) { }
