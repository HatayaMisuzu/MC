package com.mccompanion.protocol;

import java.util.Map;
import java.util.Objects;

public record CapabilityDescriptor(
        CapabilityAvailability availability,
        String version,
        Map<String, String> attributes) {

    public CapabilityDescriptor {
        Objects.requireNonNull(availability, "availability");
        if (version != null) {
            ProtocolFields.identifier(version, "version");
        }
        attributes = ProtocolFields.immutableMap(attributes == null ? Map.of() : attributes, "attributes");
        attributes.forEach((key, value) -> {
            ProtocolFields.identifier(key, "attribute key");
            ProtocolFields.text(value, "attribute value");
        });
    }

    public static CapabilityDescriptor available(String version) {
        return new CapabilityDescriptor(CapabilityAvailability.AVAILABLE, version, Map.of());
    }

    public static CapabilityDescriptor unavailable(String reason) {
        return new CapabilityDescriptor(CapabilityAvailability.UNAVAILABLE, null, Map.of("reason", reason));
    }

    public boolean available() {
        return availability == CapabilityAvailability.AVAILABLE;
    }
}
