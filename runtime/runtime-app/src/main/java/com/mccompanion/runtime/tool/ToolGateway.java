package com.mccompanion.runtime.tool;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface ToolGateway {
    List<ToolDefinition> definitions(ToolContext context);
    ToolResult execute(ToolContext context, ToolCall call);
    default ToolResult awaitTerminal(ToolContext context, ToolCall call, ToolResult accepted, Duration timeout,
                                     Consumer<ToolResult> progress) {
        return accepted;
    }
    /**
     * Returns a previously confirmed terminal result for this exact call without dispatching work.
     * Gateways that cannot prove the durable identity and effect state must return empty.
     */
    default Optional<ToolResult> reconcile(ToolContext context, ToolCall call) {
        return Optional.empty();
    }
    default void cancel(ToolContext context, String callId, String reason) { }
}
