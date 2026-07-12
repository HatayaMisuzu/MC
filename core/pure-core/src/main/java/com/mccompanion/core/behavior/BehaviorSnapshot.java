package com.mccompanion.core.behavior;

import com.mccompanion.core.failure.FailureCode;
import com.mccompanion.core.id.BehaviorId;
import com.mccompanion.core.id.CompanionId;
import com.mccompanion.core.id.TaskId;

import java.time.Instant;
import java.util.Objects;

public record BehaviorSnapshot(
        BehaviorId behaviorId,
        TaskId taskId,
        CompanionId companionId,
        String behaviorType,
        BehaviorState state,
        BehaviorRevision revision,
        long createdTick,
        long lastTick,
        Instant createdAt,
        Instant updatedAt,
        Instant endedAt,
        BehaviorState pausedFrom,
        FailureCode failureCode,
        String statusDetail) {

    public BehaviorSnapshot {
        Objects.requireNonNull(behaviorId, "behaviorId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(behaviorType, "behaviorType");
        if (behaviorType.isBlank() || behaviorType.length() > 128) {
            throw new IllegalArgumentException("behaviorType must be non-blank and at most 128 characters");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(revision, "revision");
        if (createdTick < 0 || lastTick < createdTick) {
            throw new IllegalArgumentException("ticks must be non-negative and monotonic");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot precede createdAt");
        }
        if (state.terminal() != (endedAt != null)) {
            throw new IllegalArgumentException("endedAt must be present exactly for terminal states");
        }
        if (endedAt != null && endedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("endedAt cannot precede updatedAt");
        }
        if (state == BehaviorState.PAUSED) {
            if (pausedFrom == null || pausedFrom == BehaviorState.PAUSED || pausedFrom.terminal()) {
                throw new IllegalArgumentException("paused snapshot requires a resumable prior state");
            }
        } else if (pausedFrom != null) {
            throw new IllegalArgumentException("pausedFrom is only valid while paused");
        }
        Objects.requireNonNull(failureCode, "failureCode");
        if (state == BehaviorState.FAILED
                && (failureCode == FailureCode.OK || failureCode == FailureCode.BEHAVIOR_CANCELLED)) {
            throw new IllegalArgumentException("failed behavior requires a failure code");
        }
        if (state == BehaviorState.CANCELLED && failureCode != FailureCode.BEHAVIOR_CANCELLED) {
            throw new IllegalArgumentException("cancelled behavior requires BEHAVIOR_CANCELLED");
        }
        if (state != BehaviorState.FAILED && state != BehaviorState.CANCELLED && failureCode != FailureCode.OK) {
            throw new IllegalArgumentException("non-failed behavior cannot carry a failure code");
        }
        if (statusDetail != null && (statusDetail.isBlank() || statusDetail.length() > 4_096)) {
            throw new IllegalArgumentException("statusDetail must be non-blank and at most 4096 characters");
        }
        if ((state == BehaviorState.FAILED || state == BehaviorState.CANCELLED) && statusDetail == null) {
            throw new IllegalArgumentException("failed and cancelled behaviors require a user-readable detail");
        }
    }

    public boolean terminal() {
        return state.terminal();
    }
}
