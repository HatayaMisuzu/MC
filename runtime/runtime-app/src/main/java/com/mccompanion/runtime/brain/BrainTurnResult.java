package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.tool.ToolCall;

import java.util.List;

public record BrainTurnResult(Kind kind, String response, List<ToolCall> toolCalls, String reason) {
    public enum Kind { FINAL_RESPONSE, TOOL_CALLS, ASK_USER, WAIT, CANCEL }

    public BrainTurnResult {
        if (kind == null) throw new IllegalArgumentException("brain result kind is required");
        response = response == null ? "" : response.strip();
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        reason = reason == null ? "" : reason.strip();
        if (kind == Kind.TOOL_CALLS && toolCalls.isEmpty()) throw new IllegalArgumentException("TOOL_CALLS requires calls");
        if (kind != Kind.TOOL_CALLS && !toolCalls.isEmpty()) throw new IllegalArgumentException("only TOOL_CALLS may contain calls");
        if ((kind == Kind.FINAL_RESPONSE || kind == Kind.ASK_USER) && response.isBlank()) {
            throw new IllegalArgumentException(kind + " requires a response");
        }
    }

    public static BrainTurnResult finalResponse(String response) {
        return new BrainTurnResult(Kind.FINAL_RESPONSE, response, List.of(), "");
    }

    public static BrainTurnResult tools(List<ToolCall> calls) {
        return new BrainTurnResult(Kind.TOOL_CALLS, "", calls, "");
    }
}
