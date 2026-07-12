package com.mccompanion.protocol;

public record ControlPolicy(
        boolean leaseRequired,
        long leaseTtlSeconds,
        long heartbeatIntervalSeconds,
        boolean safeIdleOnDisconnect) {

    public static final long MAX_INTERVAL_SECONDS = 86_400;

    public ControlPolicy {
        requireInterval(leaseTtlSeconds, "leaseTtlSeconds");
        requireInterval(heartbeatIntervalSeconds, "heartbeatIntervalSeconds");
    }

    private static void requireInterval(long value, String field) {
        if (value <= 0 || value > MAX_INTERVAL_SECONDS) {
            throw new IllegalArgumentException(field + " must be between 1 and " + MAX_INTERVAL_SECONDS);
        }
    }

    public static ControlPolicy safeDefaults() {
        return new ControlPolicy(true, 30, 10, true);
    }
}
