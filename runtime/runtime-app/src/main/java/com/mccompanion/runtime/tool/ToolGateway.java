package com.mccompanion.runtime.tool;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public interface ToolGateway {
    List<ToolDefinition> definitions(ToolContext context);
    ToolResult execute(ToolContext context, ToolCall call);
    default ToolResult awaitTerminal(ToolContext context, ToolCall call, ToolResult accepted, Duration timeout,
                                     Consumer<ToolResult> progress) {
        return accepted;
    }
    default void cancel(ToolContext context, String callId, String reason) { }
}
