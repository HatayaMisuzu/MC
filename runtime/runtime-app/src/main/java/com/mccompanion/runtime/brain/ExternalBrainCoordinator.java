package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.tool.DurableExecutionReceipt;
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
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Hosts only the external protocol/tool loop. It never invents a strategy or natural response. */
public final class ExternalBrainCoordinator implements AutoCloseable {
    private static final int MAX_TRACKED_DURABLE_TOOLS_PER_COMPANION = 64;
    private final ExternalBrainAdapter adapter;
    private final ToolGateway tools;
    private final int maxToolCallsPerTurn;
    private final BrainAuditRepository audit;
    private final ConversationRepository conversations;
    private final Map<String, BrainSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Object> companionLocks = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ActiveTool>> activeTools = new ConcurrentHashMap<>();
    private final Map<String, String> pendingInterruptions = new ConcurrentHashMap<>();
    private final Map<String, List<ToolResult>> interruptedObservations = new ConcurrentHashMap<>();
    private final Map<String, BrainSemanticState> semanticStates = new ConcurrentHashMap<>();
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
        Object lock = companionLocks.computeIfAbsent(question.companionId(), ignored -> new Object());
        try {
            synchronized (lock) {
                BrainSession active = sessions.get(question.companionId());
                if (active != null && !active.sessionId().equals(question.brainSessionId())) {
                    throw new IllegalStateException("BRAIN_QUESTION_SESSION_MISMATCH");
                }
                WaitingQuestion answered = conversations.answer(
                        question.questionId(), resolution.text(), resolution.optionId());
                var payload = Json.object().put("type", "user_answer")
                        .put("questionId", answered.questionId())
                        .put("optionId", answered.answer().path("optionId").asText(""))
                        .put("text", answered.answer().path("text").asText(""));
                return continueTurnLocked(controllerId, question.companionId(), Json.write(payload), context);
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("BRAIN_QUESTION_PERSISTENCE_ERROR", failure);
        }
    }

    private BrainCoordinatorResult continueTurnLocked(String controllerId, String companionId,
                                                       String userMessage, AgentContext context) {
        requireController(controllerId);
        BrainBehaviorSettings behaviorSettings = audit == null
                ? BrainBehaviorSettings.defaults(companionId) : audit.behaviorSettings(companionId);
        AgentContext baseContext = context.withBrainBehaviorSettings(behaviorSettings.toJson());
        BrainSession session = sessions.get(companionId);
        List<ToolResult> recovered = interruptedObservations.remove(companionId);
        if (recovered == null) recovered = List.of();
        if (session == null) {
            ToolContext provisional = new ToolContext(controllerId, "opening", companionId);
            BrainSessionRequest opening = new BrainSessionRequest(controllerId, companionId, baseContext,
                    tools.definitions(provisional));
            BrainSession interrupted = audit != null && adapter.supportsResume()
                    ? audit.interrupted(controllerId, companionId).orElse(null) : null;
            if (interrupted != null) {
                session = adapter.resumeSession(opening, interrupted.sessionId());
                List<ToolResult> durableRecovered = audit.undeliveredTerminal(session.sessionId());
                if (!durableRecovered.isEmpty()) {
                    List<ToolResult> combined = new ArrayList<>(recovered);
                    combined.addAll(durableRecovered);
                    recovered = List.copyOf(combined);
                }
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
        BrainSemanticState restoredState = semanticStates.get(companionId);
        if (restoredState == null && audit != null) {
            BrainAuditRepository.SemanticStateSnapshot persisted = audit.semanticState(session.sessionId());
            if (persisted != null) {
                restoredState = persisted.state();
                semanticStates.put(companionId, restoredState);
            }
        }
        AgentContext turnContext = restoredState == null ? baseContext
                : baseContext.withBrainSemanticState(restoredState.toJson());
        List<ToolResult> observations = new ArrayList<>(recovered);
        List<ToolResult> pending = recovered;
        String message = userMessage;
        int remaining = Math.max(0, maxToolCallsPerTurn - recovered.size());
        while (true) {
            List<ToolResult> submitted = pending;
            BrainTurnResult result = adapter.continueTurn(new BrainTurnRequest(session.sessionId(), message,
                    turnContext, submitted, remaining));
            if (result.semanticState() != null) {
                requireBehaviorSettings(result.semanticState(), behaviorSettings);
                semanticStates.put(companionId, result.semanticState());
                turnContext = baseContext.withBrainSemanticState(result.semanticState().toJson());
                if (audit != null) {
                    audit.semanticState(session.sessionId(), controllerId, companionId, result.semanticState());
                }
            }
            if (audit != null) {
                String submittedSessionId = session.sessionId();
                submitted.forEach(value -> audit.delivered(submittedSessionId, value.callId()));
            }
            message = "";
            pending = List.of();
            if (result.kind() != BrainTurnResult.Kind.TOOL_CALLS) {
                if (result.kind() == BrainTurnResult.Kind.FINAL_RESPONSE
                        && result.completionClaim() != null) {
                    validateCompletionClaim(toolContext, result.completionClaim(), observations);
                    if (audit != null) audit.completionClaim(session.sessionId(), result.completionClaim());
                }
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
                // The adapter cancelled the session; drop our own registration so the next
                // turn opens a fresh session instead of failing forever (fail-closed, not fail-stuck).
                sessions.remove(companionId);
                semanticStates.remove(companionId);
                if (audit != null) audit.state(session.sessionId(), "CANCELLED", "TOOL_BUDGET_EXHAUSTED");
                return new BrainCoordinatorResult(session.sessionId(), BrainTurnResult.Kind.WAIT, "",
                        "TOOL_BUDGET_EXHAUSTED", observations);
            }
            List<ToolResult> batch = new ArrayList<>();
            for (ToolCall call : result.toolCalls()) {
                BrainAuditRepository.AuditedToolCall previous = audit == null ? null
                        : audit.tool(session.sessionId(), call.callId()).orElse(null);
                ToolResult accepted;
                if (previous != null && (!previous.call().name().equals(call.name())
                        || !previous.call().arguments().equals(call.arguments()))) {
                    accepted = ToolResult.rejected(call, "DUPLICATE_CALL_ID_CONFLICT",
                            "callId was already used with different tool input");
                } else if (previous != null) {
                    accepted = previous.result();
                } else {
                    accepted = tools.execute(toolContext, call);
                }
                if (audit != null) audit.tool(session.sessionId(), call, accepted);
                var durableReceipt = DurableExecutionReceipt.fromAccepted(accepted);
                ToolResult observation;
                if (accepted.terminal()) {
                    observation = accepted;
                } else if (durableReceipt.isPresent()) {
                    ActiveTool durable = new ActiveTool(toolContext, call,
                            DurableExecutionReceipt.handle(accepted).orElse(null), Instant.now());
                    if (rememberActive(companionId, durable)) {
                        observation = durableReceipt.orElseThrow();
                    } else {
                        tools.cancel(toolContext, call.callId(), "DURABLE_EXECUTION_TRACKING_CAPACITY_EXCEEDED");
                        observation = ToolResult.rejected(call, "DURABLE_EXECUTION_TRACKING_FULL",
                                "Durable execution was cancelled because the bounded tracking capacity is full");
                    }
                } else {
                    if (!rememberActive(companionId,
                            new ActiveTool(toolContext, call, null, Instant.now()))) {
                        tools.cancel(toolContext, call.callId(), "TOOL_TRACKING_CAPACITY_EXCEEDED");
                        observation = ToolResult.rejected(call, "TOOL_TRACKING_FULL",
                                "Tool was cancelled because the bounded tracking capacity is full");
                    } else {
                        try {
                            BrainSession activeSession = session;
                            observation = tools.awaitTerminal(toolContext, call, accepted, timeout(toolContext, call),
                                    progress -> { if (audit != null) audit.tool(activeSession.sessionId(), call, progress); });
                        } finally {
                            forgetActive(companionId, call.callId());
                        }
                    }
                }
                if (!observation.terminal()) {
                    observation = ToolResult.rejected(call, "NON_TERMINAL_TOOL_RESULT",
                            "Tool gateway returned before a terminal observation");
                }
                releaseCompletedDurable(companionId, observation);
                if (audit != null) audit.tool(session.sessionId(), call, observation);
                batch.add(observation);
                observations.add(observation);
                remaining--;
                String interruption = pendingInterruptions.remove(companionId);
                if (interruption != null) {
                    interruptedObservations.merge(companionId, List.of(observation), (existing, added) -> {
                        List<ToolResult> combined = new ArrayList<>(existing);
                        combined.addAll(added);
                        return List.copyOf(combined);
                    });
                    if (audit != null) audit.state(session.sessionId(), "ACTIVE", interruption);
                    return new BrainCoordinatorResult(session.sessionId(), BrainTurnResult.Kind.WAIT, "",
                            "BRAIN_TURN_PAUSED_FOR_USER_INSTRUCTION", observations);
                }
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
        BrainSession session = sessions.remove(companionId);
        semanticStates.remove(companionId);
        pendingInterruptions.remove(companionId);
        interruptedObservations.remove(companionId);
        Map<String, ActiveTool> active = activeTools.remove(companionId);
        if (active != null) active.values().forEach(value ->
                tools.cancel(value.context(), value.call().callId(), reason));
        if (session != null) {
            adapter.cancel(session.sessionId(), reason);
            if (audit != null) audit.state(session.sessionId(), "CANCELLED", reason == null ? "CANCELLED" : reason);
        }
    }

    /**
     * Pauses only the currently awaited bounded Tool. The external Brain remains the author of
     * what to do with the new instruction; the paused terminal observation is delivered with that
     * next turn instead of letting the old turn silently continue.
     */
    public boolean pauseActiveForUserInstruction(String controllerId, String companionId, String reason) {
        requireController(controllerId);
        List<ActiveTool> active = activeFor(companionId);
        if (active.isEmpty()) return false;
        String boundedReason = reason == null || reason.isBlank()
                ? "OWNER_IMMEDIATE_INSTRUCTION" : reason.strip();
        pendingInterruptions.put(companionId, boundedReason);
        boolean accepted = active.stream().map(value ->
                value.handle() == null
                        ? tools.pause(value.context(), value.call().callId(), boundedReason)
                        : tools.pauseDurable(value.context(), value.call(), value.handle(), boundedReason))
                .reduce(false, Boolean::logicalOr);
        if (!accepted) pendingInterruptions.remove(companionId, boundedReason);
        return accepted;
    }

    public boolean yieldToOwnerActivity(String controllerId, String companionId,
                                        com.fasterxml.jackson.databind.JsonNode activity) {
        requireController(controllerId);
        List<ActiveTool> active = activeFor(companionId).stream().filter(value ->
                tools.conflictsWithOwnerActivity(value.context(), value.call().callId(), activity)).toList();
        if (active.isEmpty()) return false;
        String reason = "OWNER_SAME_TARGET_ACTIVITY";
        pendingInterruptions.put(companionId, reason);
        boolean accepted = active.stream().map(value ->
                value.handle() == null
                        ? tools.pause(value.context(), value.call().callId(), reason)
                        : tools.pauseDurable(value.context(), value.call(), value.handle(), reason))
                .reduce(false, Boolean::logicalOr);
        if (!accepted) pendingInterruptions.remove(companionId, reason);
        return accepted;
    }

    public void releaseController(String controllerId) {
        requireController(controllerId);
        List<BrainSession> cancelledSessions = List.copyOf(sessions.values());
        sessions.clear();
        semanticStates.clear();
        pendingInterruptions.clear();
        interruptedObservations.clear();
        activeTools.values().forEach(active -> active.values().forEach(value ->
                cancelActive(value, "CONTROLLER_RELEASED")));
        activeTools.clear();
        for (BrainSession session : cancelledSessions) adapter.cancel(session.sessionId(), "CONTROLLER_RELEASED");
        activeControllerId = null;
    }

    public String activeControllerId() { return activeControllerId; }
    public BrainHealth health() { return adapter.health(); }
    public BrainBehaviorSettings behaviorSettings(String companionId) {
        return audit == null ? BrainBehaviorSettings.defaults(companionId) : audit.behaviorSettings(companionId);
    }
    public BrainBehaviorSettings updateBehaviorSettings(String companionId,
                                                        BrainSemanticState.InitiativeMode initiativeMode,
                                                        BrainSemanticState.PersonalityMode personalityMode) {
        if (audit == null) throw new IllegalStateException("BRAIN_BEHAVIOR_SETTINGS_PERSISTENCE_DISABLED");
        return audit.updateBehaviorSettings(companionId, initiativeMode, personalityMode, "LOCAL_MANAGEMENT_USER");
    }

    /** Restores only executions that are still live or require reconciliation. */
    public void restoreActiveDurableCalls(List<BrainAuditRepository.DurableCall> recovered) {
        if (recovered == null) return;
        for (BrainAuditRepository.DurableCall value : recovered) {
            ToolContext context = new ToolContext(value.controllerId(), value.sessionId(), value.companionId());
            Optional<ToolResult> inspected = tools.inspectDurable(context, value.handle());
            if (inspected.isPresent()
                    && DurableExecutionReceipt.isTerminalObservation(inspected.get().observation())) {
                if (audit != null) audit.clearDurableHandle(value.sessionId(), value.handle());
                continue;
            }
            ActiveTool active = new ActiveTool(context, value.call(), value.handle(), Instant.now());
            if (rememberActive(value.companionId(), active)) {
                tools.restoreDurable(context, value.call(), value.handle());
            }
        }
    }

    private synchronized void requireController(String controllerId) {
        if (controllerId == null || controllerId.isBlank()) throw new IllegalArgumentException("controllerId is required");
        if (activeControllerId == null) activeControllerId = controllerId.strip();
        else if (!activeControllerId.equals(controllerId.strip())) {
            throw new IllegalStateException("BRAIN_CONTROLLER_ALREADY_ACTIVE");
        }
    }

    private static void requireBehaviorSettings(BrainSemanticState state, BrainBehaviorSettings settings) {
        if (state.initiativeMode() != settings.initiativeMode()
                || state.personalityMode() != settings.personalityMode()) {
            throw new IllegalArgumentException("BRAIN_SEMANTIC_STATE_POLICY_MISMATCH");
        }
    }

    private void validateCompletionClaim(ToolContext context, BrainCompletionClaim claim,
                                         List<ToolResult> turnObservations) {
        if (claim.certainty() != BrainCompletionClaim.Certainty.VERIFIED) return;
        ToolResult observation = turnObservations.stream()
                .filter(value -> value.callId().equals(claim.observationCallId()))
                .findFirst().orElse(null);
        if (observation == null && audit != null) {
            observation = audit.tool(context.brainSessionId(), claim.observationCallId())
                    .map(BrainAuditRepository.AuditedToolCall::result).orElse(null);
        }
        if (observation == null) {
            throw new IllegalArgumentException("BRAIN_FINAL_OBSERVATION_NOT_FOUND");
        }
        if (!observation.terminal() || !observation.success()) {
            throw new IllegalArgumentException("BRAIN_FINAL_OBSERVATION_NOT_VERIFIED");
        }
        String toolName = observation.toolName();
        if (!isRealObservationTool(toolName)) {
            throw new IllegalArgumentException("BRAIN_FINAL_OBSERVATION_TOOL_INVALID");
        }
        if (!claim.taskId().isBlank()
                && !claim.taskId().equals(observation.observation().path("taskId").asText(""))) {
            throw new IllegalArgumentException("BRAIN_FINAL_OBSERVATION_TASK_MISMATCH");
        }
        for (BrainCompletionClaim.EvidenceCondition condition : claim.conditions()) {
            if (!condition.matches(observation.observation())) {
                throw new IllegalArgumentException(
                        "BRAIN_FINAL_OBSERVATION_CONDITION_FAILED:" + condition.pointer());
            }
        }
    }

    private static boolean isRealObservationTool(String name) {
        if (name == null) return false;
        return name.equals("world.observe") || name.equals("world.query") || name.equals("world.scan")
                || name.equals("inventory.inspect") || name.equals("safety.inspect")
                || name.equals("task.inspect") || name.equals("task_graph.inspect")
                || name.equals("block.inspect")
                || name.equals("item.inspect") || name.equals("entity.inspect")
                || name.equals("menu.inspect");
    }

    @Override public void close() {
        if (activeControllerId != null) releaseController(activeControllerId);
        adapter.close();
    }

    private boolean rememberActive(String companionId, ActiveTool active) {
        Map<String, ActiveTool> companionTools = activeTools.computeIfAbsent(
                companionId, ignored -> new ConcurrentHashMap<>());
        synchronized (companionTools) {
            if (!companionTools.containsKey(active.call().callId())
                    && companionTools.size() >= MAX_TRACKED_DURABLE_TOOLS_PER_COMPANION) {
                return false;
            }
            companionTools.put(active.call().callId(), active);
            return true;
        }
    }

    private void forgetActive(String companionId, String callId) {
        Map<String, ActiveTool> companionTools = activeTools.get(companionId);
        if (companionTools == null) return;
        companionTools.remove(callId);
        if (companionTools.isEmpty()) activeTools.remove(companionId, companionTools);
    }

    private List<ActiveTool> activeFor(String companionId) {
        Map<String, ActiveTool> companionTools = activeTools.get(companionId);
        return companionTools == null ? List.of() : List.copyOf(companionTools.values());
    }

    int trackedDurableCount(String companionId) {
        Map<String, ActiveTool> companionTools = activeTools.get(companionId);
        return companionTools == null ? 0 : companionTools.size();
    }

    private void releaseCompletedDurable(String companionId, ToolResult observation) {
        if (!DurableExecutionReceipt.isTerminalObservation(observation.observation())) return;
        DurableExecutionReceipt.handleFromObservation(observation.observation()).ifPresent(handle -> {
            Map<String, ActiveTool> companionTools = activeTools.get(companionId);
            if (companionTools == null) return;
            for (ActiveTool active : List.copyOf(companionTools.values())) {
                if (sameHandle(active.handle(), handle)
                        && companionTools.remove(active.call().callId(), active)
                        && audit != null) {
                    audit.clearDurableHandle(active.context().brainSessionId(), handle);
                }
            }
            if (companionTools.isEmpty()) activeTools.remove(companionId, companionTools);
        });
    }

    private void cancelActive(ActiveTool active, String reason) {
        if (active.handle() == null) tools.cancel(active.context(), active.call().callId(), reason);
        else tools.cancelDurable(active.context(), active.call(), active.handle(), reason);
    }

    private static boolean sameHandle(DurableExecutionReceipt.Handle left,
                                      DurableExecutionReceipt.Handle right) {
        return left != null && right != null && left.kind().equals(right.kind())
                && left.id().equals(right.id());
    }

    private record ActiveTool(ToolContext context, ToolCall call,
                              DurableExecutionReceipt.Handle handle, Instant registeredAt) { }
}
