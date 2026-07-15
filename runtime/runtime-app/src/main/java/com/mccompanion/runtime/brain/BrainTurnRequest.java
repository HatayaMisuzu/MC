package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.tool.ToolResult;

import java.util.List;

public record BrainTurnRequest(String sessionId, String userMessage, AgentContext context,
                               List<ToolResult> toolResults, int remainingToolCalls) {
    public BrainTurnRequest {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        userMessage = userMessage == null ? "" : userMessage.strip();
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        if (remainingToolCalls < 0 || remainingToolCalls > 32) throw new IllegalArgumentException("remainingToolCalls is invalid");
    }
}
