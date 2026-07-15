package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Hosts only the external protocol/tool loop. It never invents a strategy or natural response. */
public final class ExternalBrainCoordinator implements AutoCloseable {
    private final ExternalBrainAdapter adapter;
    private final ToolGateway tools;
    private final int maxToolCallsPerTurn;
    private final BrainAuditRepository audit;
    private final Map<String, BrainSession> sessions = new HashMap<>();
    private String activeControllerId;

    public ExternalBrainCoordinator(ExternalBrainAdapter adapter, ToolGateway tools, int maxToolCallsPerTurn) {
        this(adapter, tools, maxToolCallsPerTurn, null);
    }

    public ExternalBrainCoordinator(ExternalBrainAdapter adapter, ToolGateway tools, int maxToolCallsPerTurn,
                                    BrainAuditRepository audit) {
        this.adapter = java.util.Objects.requireNonNull(adapter, "adapter");
        this.tools = java.util.Objects.requireNonNull(tools, "tools");
        if (maxToolCallsPerTurn < 1 || maxToolCallsPerTurn > 32) {
            throw new IllegalArgumentException("maxToolCallsPerTurn must be 1..32");
        }
        this.maxToolCallsPerTurn = maxToolCallsPerTurn;
        this.audit = audit;
    }

    public synchronized BrainCoordinatorResult continueTurn(String controllerId, String companionId,
                                                              String userMessage, AgentContext context) {
        requireController(controllerId);
        BrainSession session = sessions.get(companionId);
        if (session == null) {
            ToolContext provisional = new ToolContext(controllerId, "opening", companionId);
            session = adapter.openSession(new BrainSessionRequest(controllerId, companionId, context,
                    tools.definitions(provisional)));
            if (!controllerId.equals(session.controllerId()) || !companionId.equals(session.companionId())) {
                throw new IllegalStateException("external brain returned a mismatched session");
            }
            sessions.put(companionId, session);
            if (audit != null) audit.opened(session, adapter.health().adapter());
        }

        ToolContext toolContext = new ToolContext(controllerId, session.sessionId(), companionId);
        List<ToolResult> observations = new ArrayList<>();
        List<ToolResult> pending = List.of();
        String message = userMessage;
        int remaining = maxToolCallsPerTurn;
        while (true) {
            BrainTurnResult result = adapter.continueTurn(new BrainTurnRequest(session.sessionId(), message,
                    context, pending, remaining));
            message = "";
            pending = List.of();
            if (result.kind() != BrainTurnResult.Kind.TOOL_CALLS) {
                if (audit != null) audit.state(session.sessionId(), "ACTIVE",
                        result.kind() == BrainTurnResult.Kind.CANCEL ? "BRAIN_CANCELLED" : result.kind().name());
                return new BrainCoordinatorResult(session.sessionId(), result.kind(), result.response(),
                        result.kind() == BrainTurnResult.Kind.CANCEL ? "BRAIN_CANCELLED" : "OK", observations);
            }
            if (result.toolCalls().size() > remaining) {
                adapter.cancel(session.sessionId(), "TOOL_BUDGET_EXHAUSTED");
                if (audit != null) audit.state(session.sessionId(), "CANCELLED", "TOOL_BUDGET_EXHAUSTED");
                return new BrainCoordinatorResult(session.sessionId(), BrainTurnResult.Kind.WAIT, "",
                        "TOOL_BUDGET_EXHAUSTED", observations);
            }
            List<ToolResult> batch = new ArrayList<>();
            for (ToolCall call : result.toolCalls()) {
                ToolResult observation = tools.execute(toolContext, call);
                if (audit != null) audit.tool(session.sessionId(), call, observation);
                batch.add(observation);
                observations.add(observation);
                remaining--;
            }
            pending = List.copyOf(batch);
        }
    }

    public synchronized void cancel(String controllerId, String companionId, String reason) {
        requireController(controllerId);
        BrainSession session = sessions.remove(companionId);
        if (session != null) {
            adapter.cancel(session.sessionId(), reason);
            if (audit != null) audit.state(session.sessionId(), "CANCELLED", reason == null ? "CANCELLED" : reason);
        }
    }

    public synchronized void releaseController(String controllerId) {
        requireController(controllerId);
        for (BrainSession session : sessions.values()) adapter.cancel(session.sessionId(), "CONTROLLER_RELEASED");
        sessions.clear();
        activeControllerId = null;
    }

    public synchronized String activeControllerId() { return activeControllerId; }
    public BrainHealth health() { return adapter.health(); }

    private void requireController(String controllerId) {
        if (controllerId == null || controllerId.isBlank()) throw new IllegalArgumentException("controllerId is required");
        if (activeControllerId == null) activeControllerId = controllerId.strip();
        else if (!activeControllerId.equals(controllerId.strip())) {
            throw new IllegalStateException("BRAIN_CONTROLLER_ALREADY_ACTIVE");
        }
    }

    @Override public synchronized void close() {
        if (activeControllerId != null) releaseController(activeControllerId);
        adapter.close();
    }
}
