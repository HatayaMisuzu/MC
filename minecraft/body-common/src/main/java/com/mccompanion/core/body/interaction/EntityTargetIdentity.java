package com.mccompanion.core.body.interaction;

import java.util.Objects;
import java.util.UUID;

/** Stable identity retained after a bounded target reference has been resolved by Minecraft. */
public record EntityTargetIdentity(UUID uuid, Source source, String resolvedName, boolean player) {
    public EntityTargetIdentity {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(source, "source");
        resolvedName = resolvedName == null ? "" : resolvedName;
        if (source == Source.VERIFIED_PLAYER && !player) {
            throw new IllegalArgumentException("verified player target must resolve to a player");
        }
    }

    public enum Source {
        UUID,
        ENTITY_ID,
        VERIFIED_PLAYER,
        NAME
    }
}
