package com.mccompanion.core.security;

import com.mccompanion.core.id.OwnerId;

import java.util.Objects;

public record AclPrincipal(OwnerId playerId, boolean operator) {
    public AclPrincipal {
        Objects.requireNonNull(playerId, "playerId");
    }
}
