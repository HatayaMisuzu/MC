package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.tool.ToolDefinition;

import java.util.List;

public record BrainSessionRequest(String controllerId, String companionId, AgentContext context,
                                  List<ToolDefinition> tools) {
    public BrainSessionRequest {
        if (controllerId == null || controllerId.isBlank()) throw new IllegalArgumentException("controllerId is required");
        if (companionId == null || companionId.isBlank()) throw new IllegalArgumentException("companionId is required");
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
