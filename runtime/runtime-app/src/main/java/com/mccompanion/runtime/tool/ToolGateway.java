package com.mccompanion.runtime.tool;

import java.util.List;

public interface ToolGateway {
    List<ToolDefinition> definitions(ToolContext context);
    ToolResult execute(ToolContext context, ToolCall call);
    default void cancel(ToolContext context, String callId, String reason) { }
}
