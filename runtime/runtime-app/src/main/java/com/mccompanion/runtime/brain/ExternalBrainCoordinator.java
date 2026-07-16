package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;
import com.mccompanion.runtime.conversation.ConversationRepository;
import com.mccompanion.runtime.conversation.IncomingMessageKind;
import com.mccompanion.runtime.conversation.IncomingMessageResolution;
import com.mccompanion.runtime.conversation.WaitingQuestion;
import com.mccompanion.runtime.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** Hosts only the external protocol/tool loop. It never invents a strategy or natural response. */
public final class ExternalBrainCoordinator implements AutoCloseable {
    private final ExternalBrainAdapter adapter;
    private final ToolGateway tools;
    private final int maxToolCallsPerTurn;
    private final BrainAuditRepository audit;
    private final ConversationRepository conversations;
    private final Map<String, BrainSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Object> companionLocks = new ConcurrentHashMap<>();
    private final Map<String, ActiveTool> activeTools = new ConcurrentHashMap<>();
    private volatile String activeControllerId;

    public ExternalBrainCoordinator(ExternalBrainAdapter adapter, ToolGateway tools, int maxToolCallsPerTurn) {
        this(adapter, tools, maxToolCallsPerTurn, null, null);
    }

    public ExternalBrainCoordinator(ExternalBrainAdapter adapter, ToolGateway tools, int maxToolCallsPerTurn,
                                    BrainAuditRepository audit) {
        this(adapter, tools, maxToolCallsPerTurn, audit, null);
    }

    public ExternalBrainCoordinator(ExternalBrainAdapter adapter, ToolGateway tools, int maxToolCallsPerTurn,
                                    BrainAuditRepository audit, ConversationRepository conversations) {
        this.adapter = java.util.Objects.requireNonNull(adapter, "adapter");
        this.tools = java.util.Objects.requireNonNull(tools, "tools");
        if (maxToolCallsPerTurn < 1 || maxToolCallsPerTurn > 32) {
            throw new IllegalArgumentException("maxToolCallsPerTurn must be 1..32");
        }
        this.maxToolCallsPerTurn = maxToolCallsPerTurn;
        this.audit = audit;
        this.conversations = conversations;
    }

    public BrainCoordinatorResult continueTurn(String controllerId, String companionId,
                                                String userMessage, AgentContext context) {
        Object lock = companionLocks.computeIfAbsent(companionId, ignored -> new Object());
        synchronized (lock) {
            return continueTurnLocked(controllerId, companionId, userMessage, context);
        }
    }

    public BrainCoordinatorResult answer(String controllerId, WaitingQuestion question,
                                         IncomingMessageResolution resolution, AgentContext context) {
        if (question == null || question.brainSessionId() == null) {
            throw new IllegalArgumentException("External Brain question is required");
        }
        if (resolution == null || resolution.kind() != IncomingMessageKind.WAITING_ANSWER) {
            throw new IllegalArgumentException("WAITING_ANSWER resolution is required");
        }
        if (conversations == null) throw new IllegalStateException("BRAIN_QUESTION_PERSISTENCE_DISABLED");
        try {
            WaitingQuestion answered = conversations.answer(question.questionId(), resolution.text(), resolution.optionId());
            var payload = Json.object().put("type", "user_answer")
                    .put("questionId", answered.questionId())
                    .put("optionId", answered.answer().path("optionId").asText(""))
                    .put("text", answered.answer().path("text").asText(""));
            Object lock = companionLocks.computeIfAbsent(question.companionId(), ignored -> new Object());
            synchronized (lock) {
                BrainSession active = sessions.get(question.companionId());
                if (active != null && !active.sessionId().equals(question.brainSessionId())) {
                    throw new IllegalStateException("BRAIN_QUESTION_SESSION_MISMATCH");
                }
                return continueTurnLocked(controllerId, question.companionId(), Json.write(payload), context);
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("BRAIN_QUESTION_PERSISTENCE_ERROR", failure);
        }
    }

    private BrainCoordinatorResult continueTurnLocked(String controllerId, String companionId,
                                                       String userMessage, AgentContext context) {
        requireController(controllerId);
        BrainSession session = sessions.get(companionId);
        List<ToolResult> recovered = List.of();
        if (session == null) {
            ToolContext provisional = new ToolContext(controllerId, "opening", companionId);
            BrainSessionRequest opening = new BrainSessionRequest(controllerId, companionId, context,
                    tools.definitions(provisional));
            BrainSession interrupted = audit != null && adapter.supportsResume()
                    ? audit.interrupted(controllerId, companionId).orElse(null) : null;
            if (interrupted != null) {
                session = adapter.resumeSession(opening, interrupted.sessionId());
                recovered = audit.undeliveredTerminal(session.sessionId());
                audit.state(session.sessionId(), "ACTIVE", "RESUMED");
            } else {
                session = adapter.openSession(opening);
            }
            if (!controllerId.equals(session.controllerId()) || !companionId.equals(session.companionId())) {
                throw new IllegalStateException("external brain returned a mismatched session");
            }
            sessions.put(companionId, session);
            if (audit != null && interrupted == null) audit.opened(session, adapter.health().adapter());
        }

        ToolContext toolContext = new ToolContext(controllerId, session.sessionId(), companionId);
        List<ToolResult> observations = new ArrayList<>(recovered);
        List<ToolResult> pending = recovered;
        String message = userMessage;
        int remaining = Math.max(0, maxToolCallsPerTurn - recovered.size());
        while (true) {
            List<ToolResult> submitted = pending;
            BrainTurnResult result = adapter.continueTurn(new BrainTurnRequest(session.sessionId(), message,
                    context, submitted, remaining));
            if (audit != null) {
                String submittedSessionId = session.sessionId();
                submitted.forEach(value -> audit.delivered(submittedSessionId, value.callId()));
            }
            message = "";
            pending = List.of();
            if (result.kind() != BrainTurnResult.Kind.TOOL_CALLS) {
                if (audit != null) audit.state(session.sessionId(), "ACTIVE",
                        result.kind() == BrainTurnResult.Kind.CANCEL ? "BRAIN_CANCELLED" : result.kind().name());
                WaitingQuestion question = null;
                if (result.kind() == BrainTurnResult.Kind.ASK_USER) {
                    if (conversations == null) throw new IllegalStateException("BRAIN_QUESTION_PERSISTENCE_DISABLED");
                    try {
                        BrainQuestion requested = result.question();
                        String taskId = requested.taskId() != null && observations.stream().anyMatch(value ->
                                requested.taskId().equals(value.observation().path("taskId").asText(null)))
                                ? requested.taskId() : null;
                        question = conversations.askBrain(companionId, session.sessionId(), taskId,
                                requested.prompt(), requested.reason(), requested.options(), requested.freeTextAllowed(),
                                requested.context(), null);
                    } catch (java.sql.SQLException failure) {
                        throw new IllegalStateException("BRAIN_QUESTION_PERSISTENCE_ERROR", failure);
                    }
                }
                return new BrainCoordinatorResult(session.sessionId(), result.kind(), result.response(),
                        result.kind() == BrainTurnResult.Kind.CANCEL ? "BRAIN_CANCELLED" : "OK", observations, question);
            }
            if (result.toolCalls().size() > remaining) {
                adapter.cancel(session.sessionId(), "TOOL_BUDGET_EXHAUSTED");
                if (audit != null) audit.state(session.sessionId(), "CANCELLED", "TOOL_BUDGET_EXHAUSTED");
                return new BrainCoordinatorResult(session.sessionId(), BrainTurnResult.Kind.WAIT, "",
                        "TOOL_BUDGET_EXHAUSTED", observations);
            }
            List<ToolResult> batch = new ArrayList<>();
            for (ToolCall call : result.toolCalls()) {
                ToolResult accepted = tools.execute(toolContext, call);
                if (audit != null) audit.tool(session.sessionId(), call, accepted);
                ToolResult observation;
                if (accepted.terminal()) {
                    observation = accepted;
                } else {
                    activeTools.put(companionId, new ActiveTool(toolContext, call));
                    try {
                        BrainSession activeSession = session;
                        observation = tools.awaitTerminal(toolContext, call, accepted, timeout(toolContext, call),
                                progress -> { if (audit != null) audit.tool(activeSession.sessionId(), call, progress); });
                    } finally {
                        activeTools.remove(companionId);
                    }
                }
                if (!observation.terminal()) {
                    observation = ToolResult.rejected(call, "NON_TERMINAL_TOOL_RESULT",
                            "Tool gateway returned before a terminal observation");
                }
                if (audit != null) audit.tool(session.sessionId(), call, observation);
                batch.add(observation);
                observations.add(observation);
                remaining--;
            }
            if (sessions.get(companionId) != session) {
                return new BrainCoordinatorResult(session.sessionId(), BrainTurnResult.Kind.CANCEL, "",
                        "BRAIN_CANCELLED", observations);
            }
            pending = List.copyOf(batch);
        }
    }

    private Duration timeout(ToolContext context, ToolCall call) {
        return tools.definitions(context).stream().filter(value -> value.name().equals(call.name()))
                .findFirst().map(value -> value.timeout()).orElse(Duration.ofSeconds(30));
    }

    public void cancel(String controllerId, String companionId, String reason) {
        requireController(controllerId);
        ActiveTool active = activeTools.get(companionId);
        if (active != null) tools.cancel(active.context(), active.call().callId(), reason);
        BrainSession session = sessions.remove(companionId);
        if (session != null) {
            adapter.cancel(session.sessionId(), reason);
            if (audit != null) audit.state(session.sessionId(), "CANCELLED", reason == null ? "CANCELLED" : reason);
        }
    }

    public void releaseController(String controllerId) {
        requireController(controllerId);
        activeTools.values().forEach(active -> tools.cancel(active.context(), active.call().callId(),
                "CONTROLLER_RELEASED"));
        for (BrainSession session : sessions.values()) adapter.cancel(session.sessionId(), "CONTROLLER_RELEASED");
        sessions.clear();
        activeControllerId = null;
    }

    public String activeControllerId() { return activeControllerId; }
    public BrainHealth health() { return adapter.health(); }

    private synchronized void requireController(String controllerId) {
        if (controllerId == null || controllerId.isBlank()) throw new IllegalArgumentException("controllerId is required");
        if (activeControllerId == null) activeControllerId = controllerId.strip();
        else if (!activeControllerId.equals(controllerId.strip())) {
            throw new IllegalStateException("BRAIN_CONTROLLER_ALREADY_ACTIVE");
        }
    }

    @Override public void close() {
        if (activeControllerId != null) releaseController(activeControllerId);
        adapter.close();
    }

    private record ActiveTool(ToolContext context, ToolCall call) { }
}
