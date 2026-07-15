package com.mccompanion.runtime.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.session.Handshake;

import java.util.List;

/** Computes Registry ∩ formal implementation ∩ connected body declaration ∩ current body state. */
public final class CapabilityVisibility {
    private final CapabilityRegistry registry;

    public CapabilityVisibility(CapabilityRegistry registry) {
        this.registry = registry;
    }

    public Snapshot resolve(Handshake handshake, JsonNode companionStatus) {
        List<CapabilityStatus> statuses = registry.definitions().stream()
                .map(definition -> status(definition, handshake, companionStatus))
                .toList();
        return new Snapshot(statuses);
    }

    private static CapabilityStatus status(CapabilityDefinition definition, Handshake handshake, JsonNode status) {
        if (!definition.implemented()) {
            return value(definition, CapabilityLifecycleState.DECLARED, "RUNTIME_NOT_IMPLEMENTED");
        }
        if (handshake == null) {
            return value(definition, CapabilityLifecycleState.IMPLEMENTED, "BODY_NOT_CONNECTED");
        }
        if (!"fabric".equalsIgnoreCase(handshake.loader()) || !"1.21.1".equals(handshake.minecraftVersion())) {
            return value(definition, CapabilityLifecycleState.UNSUPPORTED, "LOADER_OR_VERSION_UNSUPPORTED");
        }
        if (!bodyDeclares(handshake.capabilities(), definition.name())) {
            return value(definition, CapabilityLifecycleState.UNSUPPORTED, "BODY_DID_NOT_DECLARE_CAPABILITY");
        }
        if (status == null || status.isMissingNode() || status.isNull() || status.isEmpty()) {
            return value(definition, CapabilityLifecycleState.CONNECTED, "WAITING_FOR_COMPANION_STATUS");
        }
        if (!status.path("runtimeConnected").asBoolean(false)) {
            return value(definition, CapabilityLifecycleState.TEMPORARILY_BLOCKED, "RUNTIME_DISCONNECTED");
        }
        if (!"spawned".equalsIgnoreCase(status.path("bodyState").asText(""))) {
            return value(definition, CapabilityLifecycleState.TEMPORARILY_BLOCKED, "BODY_NOT_SPAWNED");
        }
        return value(definition, CapabilityLifecycleState.AVAILABLE_NOW, "");
    }

    private static CapabilityStatus value(CapabilityDefinition definition, CapabilityLifecycleState state, String reason) {
        return new CapabilityStatus(definition.name(), state, reason);
    }

    private static boolean bodyDeclares(JsonNode capabilities, String name) {
        JsonNode value = capabilities == null ? null : capabilities.get(name);
        if (value == null || value.isNull()) return false;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isTextual()) return value.asText().equalsIgnoreCase("AVAILABLE")
                || value.asText().equalsIgnoreCase("AVAILABLE_NOW");
        if (!value.isObject()) return false;
        return value.path("available").asBoolean(false)
                || value.path("enabled").asBoolean(false)
                || value.path("availability").asText("").equalsIgnoreCase("AVAILABLE")
                || value.path("state").asText("").equalsIgnoreCase("AVAILABLE_NOW");
    }

    public record Snapshot(List<CapabilityStatus> statuses) {
        public Snapshot { statuses = List.copyOf(statuses); }
        public List<String> availableNames() { return statuses.stream().filter(CapabilityStatus::availableNow)
                .map(CapabilityStatus::name).toList(); }
        public ObjectNode toJson() {
            ObjectNode result = Json.object();
            for (CapabilityStatus status : statuses) {
                ObjectNode value = result.putObject(status.name()).put("state", status.state().name());
                if (!status.reason().isBlank()) value.put("reason", status.reason());
            }
            return result;
        }
    }
}
