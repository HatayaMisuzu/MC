package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.tool.ToolResult;

import java.util.List;

public record BrainCoordinatorResult(String sessionId, BrainTurnResult.Kind kind, String response,
                                     String code, List<ToolResult> toolResults) {
    public BrainCoordinatorResult {
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        response = response == null ? "" : response;
        code = code == null ? "OK" : code;
    }
}
