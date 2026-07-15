package com.mccompanion.runtime.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.agent.RiskLevel;
import com.mccompanion.runtime.json.Json;

public record CapabilityDefinition(String name, String description, JsonNode parameterSchema,
                                   RiskLevel risk, boolean cancellable, boolean recoverable, boolean implemented) {
    public CapabilityDefinition(String name, String description, JsonNode parameterSchema,
                                RiskLevel risk, boolean cancellable, boolean recoverable) {
        this(name, description, parameterSchema, risk, cancellable, recoverable, false);
    }

    public CapabilityDefinition {
        if (name == null || !name.matches("[A-Z][A-Za-z0-9]+")) throw new IllegalArgumentException("Invalid capability name");
        description = description == null ? "" : description.strip();
        parameterSchema = parameterSchema == null ? Json.object() : parameterSchema.deepCopy();
        risk = risk == null ? RiskLevel.LOW : risk;
    }
}
