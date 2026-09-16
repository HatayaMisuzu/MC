package com.mccompanion.protocol;

import java.util.Objects;

/** Complete session declaration; revisions never merge partial or stale capability sets. */
public record SessionCapabilitySnapshot(long revision, CapabilitySet capabilities) {
    public SessionCapabilitySnapshot {
        if (revision < 0) throw new IllegalArgumentException("capability revision must be non-negative");
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.size() > 256) throw new IllegalArgumentException("too many capabilities");
    }

    public boolean permits(String name, String version) {
        return capabilities.find(name).filter(CapabilityDescriptor::available)
                .filter(value -> Objects.equals(version, value.version())).isPresent();
    }
}
