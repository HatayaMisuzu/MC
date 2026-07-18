package com.mccompanion.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record CommandEnvelope(
        String commandId,
        CommandType command,
        String companionId,
        String taskId,
        String leaseId,
        long controlEpoch,
        long expectedRevision,
        Map<String, JsonNode> arguments) {

    public CommandEnvelope {
        commandId = ProtocolFields.identifier(commandId, "commandId");
        Objects.requireNonNull(command, "command");
        companionId = ProtocolFields.identifier(companionId, "companionId");
        if (taskId != null) {
            taskId = ProtocolFields.identifier(taskId, "taskId");
        }
        if (leaseId != null) {
            leaseId = ProtocolFields.identifier(leaseId, "leaseId");
        }
        if (controlEpoch < 0) {
            throw new IllegalArgumentException("controlEpoch must be non-negative");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }
        boolean leaseProtected = switch (command) {
            case START_BEHAVIOR, PAUSE_BEHAVIOR, RESUME_BEHAVIOR, CANCEL_BEHAVIOR,
                    RENEW_LEASE, RELEASE_LEASE -> true;
            case QUERY_STATUS, QUERY_REGISTRY, QUERY_RECIPE, QUERY_OBSERVATION, ACQUIRE_LEASE -> false;
        };
        if (leaseProtected && (leaseId == null || controlEpoch == 0)) {
            throw new IllegalArgumentException(command + " requires a leaseId and a positive controlEpoch");
        }
        if (!leaseProtected && (leaseId != null || controlEpoch != 0)) {
            throw new IllegalArgumentException(command + " must not carry an existing control lease");
        }
        if (command == CommandType.START_BEHAVIOR && taskId == null) {
            throw new IllegalArgumentException("START_BEHAVIOR requires a taskId");
        }
        TreeMap<String, JsonNode> copy = new TreeMap<>();
        if (arguments != null) {
            arguments.forEach((key, value) -> {
                String checkedKey = ProtocolFields.identifier(key, "argument name");
                JsonNode checkedValue = Objects.requireNonNull(value, "argument value");
                copy.put(checkedKey, checkedValue.deepCopy());
            });
        }
        arguments = Collections.unmodifiableMap(copy);
    }

    @Override
    public Map<String, JsonNode> arguments() {
        TreeMap<String, JsonNode> copy = new TreeMap<>();
        arguments.forEach((key, value) -> copy.put(key, value.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }
}
