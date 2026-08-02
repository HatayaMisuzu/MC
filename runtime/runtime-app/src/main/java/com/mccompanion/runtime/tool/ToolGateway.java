package com.mccompanion.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
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
    /** Re-reads a durable execution after Runtime restart without starting new work. */
    default Optional<ToolResult> inspectDurable(ToolContext context, DurableExecutionReceipt.Handle handle) {
        return Optional.empty();
    }
    /** Rebinds gateway-local control state to a durable execution recovered from the audit. */
    default void restoreDurable(ToolContext context, ToolCall call, DurableExecutionReceipt.Handle handle) { }
    /** Cancels the exact durable handle; the default keeps legacy call-id behavior. */
    default void cancelDurable(ToolContext context, ToolCall call,
                               DurableExecutionReceipt.Handle handle, String reason) {
        cancel(context, call.callId(), reason);
    }
    /**
     * Requests a safe pause of an in-flight call. Returns true only when this gateway owns the
     * call and accepted the pause request. This is deterministic interruption plumbing, not a
     * decision about whether user text should interrupt work.
     */
    default boolean pause(ToolContext context, String callId, String reason) { return false; }
    /** Pauses the exact durable handle; the default keeps legacy call-id behavior. */
    default boolean pauseDurable(ToolContext context, ToolCall call,
                                 DurableExecutionReceipt.Handle handle, String reason) {
        return pause(context, call.callId(), reason);
    }
    /** Returns true only when a verified owner activity targets this call's active exact target. */
    default boolean conflictsWithOwnerActivity(ToolContext context, String callId, JsonNode activity) {
        return false;
    }
}
