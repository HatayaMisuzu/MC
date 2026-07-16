package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.tool.ToolCall;

import java.util.List;

public record BrainTurnResult(Kind kind, String response, List<ToolCall> toolCalls, String reason,
                              BrainQuestion question) {
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
        if (kind == Kind.ASK_USER && question == null) throw new IllegalArgumentException("ASK_USER requires question");
        if (kind != Kind.ASK_USER && question != null) throw new IllegalArgumentException("only ASK_USER may contain question");
    }

    public BrainTurnResult(Kind kind, String response, List<ToolCall> toolCalls, String reason) {
        this(kind, response, toolCalls, reason, null);
    }

    public static BrainTurnResult finalResponse(String response) {
        return new BrainTurnResult(Kind.FINAL_RESPONSE, response, List.of(), "", null);
    }

    public static BrainTurnResult tools(List<ToolCall> calls) {
        return new BrainTurnResult(Kind.TOOL_CALLS, "", calls, "", null);
    }

    public static BrainTurnResult askUser(BrainQuestion question) {
        return new BrainTurnResult(Kind.ASK_USER, question.prompt(), List.of(), question.reason(), question);
    }
}
