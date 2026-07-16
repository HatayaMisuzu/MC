package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.tool.ToolResult;
import com.mccompanion.runtime.conversation.WaitingQuestion;

import java.util.List;

public record BrainCoordinatorResult(String sessionId, BrainTurnResult.Kind kind, String response,
                                     String code, List<ToolResult> toolResults, WaitingQuestion question) {
    public BrainCoordinatorResult {
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        response = response == null ? "" : response;
        code = code == null ? "OK" : code;
    }

    public BrainCoordinatorResult(String sessionId, BrainTurnResult.Kind kind, String response,
                                  String code, List<ToolResult> toolResults) {
        this(sessionId, kind, response, code, toolResults, null);
    }
}
