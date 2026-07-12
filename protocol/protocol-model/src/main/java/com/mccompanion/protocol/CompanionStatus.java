package com.mccompanion.protocol;

import java.time.Instant;
import java.util.Objects;

public record CompanionStatus(
        String companionId,
        String ownerId,
        String displayName,
        String worldId,
        String dimension,
        PositionDto position,
        CompanionBodyState bodyState,
        String behaviorId,
        ProtocolBehaviorState behaviorState,
        long behaviorRevision,
        long controlEpoch,
        boolean runtimeConnected,
        CapabilitySet capabilities,
        Instant observedAt) {

    public CompanionStatus {
        companionId = ProtocolFields.identifier(companionId, "companionId");
        ownerId = ProtocolFields.identifier(ownerId, "ownerId");
        displayName = ProtocolFields.text(displayName, "displayName");
        worldId = ProtocolFields.identifier(worldId, "worldId");
        dimension = ProtocolFields.identifier(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(bodyState, "bodyState");
        if (behaviorId != null) {
            behaviorId = ProtocolFields.identifier(behaviorId, "behaviorId");
            Objects.requireNonNull(behaviorState, "behaviorState");
        } else if (behaviorState != null) {
            throw new IllegalArgumentException("behaviorState requires behaviorId");
        } else if (behaviorRevision != 0) {
            throw new IllegalArgumentException("behaviorRevision must be zero when no behavior is present");
        }
        if (behaviorRevision < 0 || controlEpoch < 0) {
            throw new IllegalArgumentException("behaviorRevision and controlEpoch must be non-negative");
        }
        capabilities = capabilities == null ? CapabilitySet.empty() : capabilities;
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
