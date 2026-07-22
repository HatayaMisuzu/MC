package com.mccompanion.runtime.brain;

/** Stable, privacy-safe classifications used by live Brain validation and product telemetry. */
public enum LiveBrainFailureCategory {
    AUTH,
    RATE_LIMIT,
    NETWORK,
    TIMEOUT,
    PROVIDER_SCHEMA,
    MODEL_BEHAVIOR,
    TOOL_SELECTION,
    TOOL_ARGUMENT,
    OBSERVATION_INSUFFICIENT,
    UNSUPPORTED_CAPABILITY,
    PRODUCT_BUG,
    USER_CANCELLED
}
