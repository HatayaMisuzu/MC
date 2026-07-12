package com.mccompanion.core.action;

import com.mccompanion.core.failure.FailureCode;
import com.mccompanion.core.id.ActionId;
import com.mccompanion.core.id.BehaviorId;
import com.mccompanion.core.id.CompanionId;
import com.mccompanion.core.id.TaskId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ActionEvidenceBuilder {
    private final ActionId actionId;
    private final BehaviorId behaviorId;
    private final TaskId taskId;
    private final CompanionId companionId;
    private final long startTick;
    private final PositionSnapshot beforePosition;
    private final InventoryDigest beforeInventory;
    private final Instant startedAt;
    private final Map<String, String> metadata = new LinkedHashMap<>();
    private boolean finished;

    private ActionEvidenceBuilder(
            ActionId actionId,
            BehaviorId behaviorId,
            TaskId taskId,
            CompanionId companionId,
            long startTick,
            PositionSnapshot beforePosition,
            InventoryDigest beforeInventory,
            Instant startedAt) {
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.behaviorId = Objects.requireNonNull(behaviorId, "behaviorId");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.companionId = Objects.requireNonNull(companionId, "companionId");
        if (startTick < 0) {
            throw new IllegalArgumentException("startTick must be non-negative");
        }
        this.startTick = startTick;
        this.beforePosition = Objects.requireNonNull(beforePosition, "beforePosition");
        this.beforeInventory = Objects.requireNonNull(beforeInventory, "beforeInventory");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    public static ActionEvidenceBuilder begin(
            ActionId actionId,
            BehaviorId behaviorId,
            TaskId taskId,
            CompanionId companionId,
            long startTick,
            PositionSnapshot beforePosition,
            InventoryDigest beforeInventory,
            Instant startedAt) {
        return new ActionEvidenceBuilder(actionId, behaviorId, taskId, companionId, startTick,
                beforePosition, beforeInventory, startedAt);
    }

    public ActionEvidenceBuilder metadata(String key, String value) {
        ensureOpen();
        validateMetadata(key, value);
        if (metadata.putIfAbsent(key, value) != null) {
            throw new IllegalArgumentException("duplicate metadata key: " + key);
        }
        return this;
    }

    public ActionEvidence finish(
            long endTick,
            PositionSnapshot afterPosition,
            InventoryDigest afterInventory,
            FailureCode failureCode,
            PlayerActionPath playerPathUsed,
            boolean forbiddenWriteDetected,
            Instant endedAt) {
        ensureOpen();
        ActionEvidence evidence = new ActionEvidence(actionId, behaviorId, taskId, companionId, startTick, endTick,
                beforePosition, afterPosition, beforeInventory, afterInventory,
                failureCode == FailureCode.OK, failureCode, playerPathUsed, forbiddenWriteDetected,
                startedAt, endedAt, metadata);
        finished = true;
        return evidence;
    }

    private void ensureOpen() {
        if (finished) {
            throw new IllegalStateException("action evidence has already been finished");
        }
    }

    private static void validateMetadata(String key, String value) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("metadata key must be non-blank and at most 128 characters");
        }
        if (value == null || value.length() > 4_096) {
            throw new IllegalArgumentException("metadata value must be at most 4096 characters");
        }
    }
}
