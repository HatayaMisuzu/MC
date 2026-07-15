package com.mccompanion.runtime.tool;

import java.util.List;

public final class CompositeToolGateway implements ToolGateway, AutoCloseable {
    private final List<ToolGateway> delegates;
    public CompositeToolGateway(List<ToolGateway> delegates) { this.delegates = List.copyOf(delegates); }
    @Override public List<ToolDefinition> definitions(ToolContext context) {
        return delegates.stream().flatMap(value -> value.definitions(context).stream()).toList();
    }
    @Override public ToolResult execute(ToolContext context, ToolCall call) {
        return delegates.stream().filter(value -> value.definitions(context).stream()
                        .anyMatch(definition -> definition.name().equals(call.name())))
                .findFirst().map(value -> value.execute(context, call))
                .orElseGet(() -> ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Tool is not AVAILABLE_NOW"));
    }
    @Override public void close() {
        delegates.forEach(value -> { if (value instanceof AutoCloseable closeable) try { closeable.close(); } catch (Exception ignored) { } });
    }
}
