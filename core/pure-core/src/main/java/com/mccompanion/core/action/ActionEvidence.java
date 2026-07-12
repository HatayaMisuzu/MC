package com.mccompanion.core.action;

import com.mccompanion.core.failure.FailureCode;
import com.mccompanion.core.id.ActionId;
import com.mccompanion.core.id.BehaviorId;
import com.mccompanion.core.id.CompanionId;
import com.mccompanion.core.id.TaskId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ActionEvidence(
        ActionId actionId,
        BehaviorId behaviorId,
        TaskId taskId,
        CompanionId companionId,
        long startTick,
        long endTick,
        PositionSnapshot beforePosition,
        PositionSnapshot afterPosition,
        InventoryDigest beforeInventory,
        InventoryDigest afterInventory,
        boolean success,
        FailureCode failureCode,
        PlayerActionPath playerPathUsed,
        boolean forbiddenWriteDetected,
        Instant startedAt,
        Instant endedAt,
        Map<String, String> metadata) {

    public ActionEvidence {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(behaviorId, "behaviorId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(companionId, "companionId");
        if (startTick < 0 || endTick < startTick) {
            throw new IllegalArgumentException("action ticks must be non-negative and monotonic");
        }
        Objects.requireNonNull(beforePosition, "beforePosition");
        Objects.requireNonNull(afterPosition, "afterPosition");
        Objects.requireNonNull(beforeInventory, "beforeInventory");
        Objects.requireNonNull(afterInventory, "afterInventory");
        Objects.requireNonNull(failureCode, "failureCode");
        if (success != failureCode.success()) {
            throw new IllegalArgumentException("success must agree with failureCode");
        }
        Objects.requireNonNull(playerPathUsed, "playerPathUsed");
        if (forbiddenWriteDetected != (failureCode == FailureCode.FORBIDDEN_WRITE_DETECTED)) {
            throw new IllegalArgumentException("forbiddenWriteDetected must agree with the failure code");
        }
        boolean auditedStateChanged = !beforePosition.equals(afterPosition)
                || !beforeInventory.equals(afterInventory);
        if (auditedStateChanged && playerPathUsed == PlayerActionPath.NONE) {
            throw new IllegalArgumentException("state changes must identify the normal player path used");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt cannot precede startedAt");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        metadata.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 128) {
                throw new IllegalArgumentException("metadata keys must be non-blank and at most 128 characters");
            }
            if (value == null || value.length() > 4_096) {
                throw new IllegalArgumentException("metadata values must be at most 4096 characters");
            }
        });
    }

    public boolean changedWorld() {
        return !beforePosition.worldId().equals(afterPosition.worldId())
                || !beforePosition.dimension().equals(afterPosition.dimension());
    }
}
