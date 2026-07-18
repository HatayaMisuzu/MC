package com.mccompanion.runtime.tool;

import java.time.Duration;
import java.util.List;

public final class CompositeToolGateway implements ToolGateway, AutoCloseable {
    private final List<ToolGateway> delegates;
    public CompositeToolGateway(List<ToolGateway> delegates) { this.delegates = List.copyOf(delegates); }
    @Override public List<ToolDefinition> definitions(ToolContext context) {
        return delegates.stream().flatMap(value -> value.definitions(context).stream()).toList();
    }
    @Override public ToolResult execute(ToolContext context, ToolCall call) {
        return delegate(context, call).map(value -> value.execute(context, call))
                .orElseGet(() -> ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Tool is not AVAILABLE_NOW"));
    }
    @Override public ToolResult awaitTerminal(ToolContext context, ToolCall call, ToolResult accepted, Duration timeout,
                                              java.util.function.Consumer<ToolResult> progress) {
        return delegate(context, call).map(value -> value.awaitTerminal(context, call, accepted, timeout, progress))
                .orElse(accepted);
    }
    @Override public java.util.Optional<ToolResult> reconcile(ToolContext context, ToolCall call) {
        return delegate(context, call).flatMap(value -> value.reconcile(context, call));
    }
    @Override public void cancel(ToolContext context, String callId, String reason) {
        delegates.forEach(value -> value.cancel(context, callId, reason));
    }
    private java.util.Optional<ToolGateway> delegate(ToolContext context, ToolCall call) {
        return delegates.stream().filter(value -> value.definitions(context).stream()
                .anyMatch(definition -> definition.name().equals(call.name()))).findFirst();
    }
    @Override public void close() {
        delegates.forEach(value -> { if (value instanceof AutoCloseable closeable) try { closeable.close(); } catch (Exception ignored) { } });
    }
}
