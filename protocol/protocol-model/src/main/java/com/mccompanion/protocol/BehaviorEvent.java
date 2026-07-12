package com.mccompanion.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.Objects;

public record BehaviorEvent(
        String eventId,
        String behaviorId,
        String commandId,
        String companionId,
        BehaviorEventType event,
        ProtocolBehaviorState state,
        long revision,
        long tick,
        double progress,
        String failureCode,
        String message,
        Instant occurredAt,
        JsonNode snapshot) {

    public BehaviorEvent {
        eventId = ProtocolFields.identifier(eventId, "eventId");
        behaviorId = ProtocolFields.identifier(behaviorId, "behaviorId");
        if (commandId != null) {
            commandId = ProtocolFields.identifier(commandId, "commandId");
        }
        companionId = ProtocolFields.identifier(companionId, "companionId");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(state, "state");
        validateEventState(event, state);
        if (revision < 0 || tick < 0) {
            throw new IllegalArgumentException("revision and tick must be non-negative");
        }
        if (!Double.isFinite(progress) || progress < 0.0 || progress > 1.0) {
            throw new IllegalArgumentException("progress must be finite and between 0 and 1");
        }
        boolean failed = state == ProtocolBehaviorState.FAILED || event == BehaviorEventType.FAILED;
        if (failed) {
            failureCode = ProtocolFields.identifier(failureCode, "failureCode");
            message = ProtocolFields.text(message, "message");
        } else if (failureCode != null) {
            throw new IllegalArgumentException("failureCode is only valid for a failed behavior event");
        }
        if (message != null) {
            message = ProtocolFields.text(message, "message");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        snapshot = snapshot == null ? JsonNodeFactory.instance.objectNode() : snapshot.deepCopy();
        if (!snapshot.isObject()) {
            throw new IllegalArgumentException("snapshot must be a JSON object");
        }
    }

    @Override
    public JsonNode snapshot() {
        return snapshot.deepCopy();
    }

    private static void validateEventState(BehaviorEventType event, ProtocolBehaviorState state) {
        boolean valid = switch (event) {
            case STARTED -> state == ProtocolBehaviorState.STARTING
                    || state == ProtocolBehaviorState.RUNNING;
            case PROGRESS -> state == ProtocolBehaviorState.RUNNING;
            case WAITING -> state == ProtocolBehaviorState.WAITING;
            case PAUSED -> state == ProtocolBehaviorState.PAUSED;
            case RESUMED -> state == ProtocolBehaviorState.STARTING
                    || state == ProtocolBehaviorState.RUNNING
                    || state == ProtocolBehaviorState.WAITING
                    || state == ProtocolBehaviorState.BLOCKED;
            case BLOCKED -> state == ProtocolBehaviorState.BLOCKED;
            case COMPLETED -> state == ProtocolBehaviorState.COMPLETED;
            case FAILED -> state == ProtocolBehaviorState.FAILED;
            case CANCELLED -> state == ProtocolBehaviorState.CANCELLED;
        };
        if (!valid) {
            throw new IllegalArgumentException("behavior event " + event + " is inconsistent with state " + state);
        }
    }
}
