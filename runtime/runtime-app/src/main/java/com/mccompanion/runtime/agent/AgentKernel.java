package com.mccompanion.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.command.CommandReply;
import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.capability.CapabilityVisibility;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.memory.MemoryRepository;
import com.mccompanion.runtime.provider.ProviderRouter;
import com.mccompanion.runtime.session.CompanionRecord;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.task.TaskRecord;
import com.mccompanion.runtime.task.TaskState;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Executes one reusable capability at a time and advances only from deterministic task observations. */
public final class AgentKernel implements CommandService.TaskLifecycleListener, AutoCloseable {
    private final AgentPlanRepository plans;
    private final CommandService commands;
    private final RuntimeLog log;
    private final ProviderRouter providers;
    private final CompanionRepository companions;
    private final SessionRegistry sessions;
    private final CapabilityVisibility capabilityVisibility;
    private final MemoryRepository memories;
    private final ExecutorService replanner;
    private final CapabilityIntentTranslator translator = new CapabilityIntentTranslator();

    public AgentKernel(AgentPlanRepository plans, CommandService commands, RuntimeLog log) {
        this(plans, commands, log, null, null, null, null, null);
    }

    public AgentKernel(AgentPlanRepository plans, CommandService commands, RuntimeLog log,
                       ProviderRouter providers, CompanionRepository companions, SessionRegistry sessions,
                       CapabilityVisibility capabilityVisibility) {
        this(plans, commands, log, providers, companions, sessions, capabilityVisibility, null);
    }

    public AgentKernel(AgentPlanRepository plans, CommandService commands, RuntimeLog log,
                       ProviderRouter providers, CompanionRepository companions, SessionRegistry sessions,
                       CapabilityVisibility capabilityVisibility, MemoryRepository memories) {
        this.plans = plans; this.commands = commands; this.log = log; this.providers = providers;
        this.companions = companions; this.sessions = sessions; this.capabilityVisibility = capabilityVisibility;
        this.memories = memories;
        this.replanner = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mc-companion-agent-replanner");
            thread.setDaemon(false);
            return thread;
        });
    }

    public synchronized DurablePlan start(String planId) throws SQLException {
        DurablePlan plan = plans.get(planId).orElseThrow(() -> new IllegalArgumentException("PLAN_NOT_FOUND"));
        if (plan.state() != StepState.READY) return plan;
        DurablePlan updated = dispatch(plan);
        if (updated.state() == StepState.BLOCKED) scheduleReplan(updated);
        return updated;
    }

    private DurablePlan dispatch(DurablePlan plan) throws SQLException {
        DurablePlan.DurableStep step = plan.steps().get(plan.currentStep());
        plan = plans.transitionStep(plan.planId(), plan.revision(), step.index(), StepState.RUNNING, Json.object(), null);
        step = plan.steps().get(plan.currentStep());
        Intent intent = translator.translate(step.definition(), plan.requestText()).orElse(null);
        if (intent == null) {
            return plans.transitionStep(plan.planId(), plan.revision(), step.index(), StepState.BLOCKED,
                    Json.object().put("capability", step.definition().capability())
                            .put("message", "当前 Fabric 身体尚未提供该正式能力；未执行任何替代或作弊动作。"),
                    "CAPABILITY_UNAVAILABLE");
        }
        CommandReply reply = commands.execute("agent-" + UUID.randomUUID(), plan.companionId(), intent);
        if (!reply.accepted() || reply.taskId() == null) {
            return plans.transitionStep(plan.planId(), plan.revision(), step.index(), StepState.BLOCKED,
                    reply.toJson(), reply.code());
        }
        return plans.linkTask(plan.planId(), plan.revision(), step.index(), reply.taskId());
    }

    @Override public synchronized void onTaskUpdated(TaskRecord task, JsonNode observation) {
        if (!task.state().terminal() && task.state() != TaskState.BLOCKED) return;
        try {
            DurablePlan plan = plans.forTask(task.taskId()).orElse(null);
            if (plan == null || plan.state().terminal()) return;
            DurablePlan.DurableStep step = plan.steps().get(plan.currentStep());
            StepState next = switch (task.state()) {
                case COMPLETED -> StepState.SUCCEEDED;
                case CANCELLED -> StepState.CANCELLED;
                case FAILED -> StepState.BLOCKED;
                default -> StepState.BLOCKED;
            };
            String failure = next == StepState.SUCCEEDED ? null : observation.path("code").asText(task.state().name());
            DurablePlan updated = plans.transitionStep(plan.planId(), plan.revision(), step.index(), next, observation, failure);
            if (updated.state() == StepState.READY) {
                DurablePlan dispatched = dispatch(updated);
                if (dispatched.state() == StepState.BLOCKED) scheduleReplan(dispatched);
            } else if (updated.state() == StepState.BLOCKED) {
                scheduleReplan(updated);
            }
        } catch (SQLException | RuntimeException failure) {
            log.error("Agent plan could not apply task observation: task=" + task.taskId(), failure);
        }
    }

    private void scheduleReplan(DurablePlan blocked) {
        if (providers == null || companions == null || sessions == null || capabilityVisibility == null) return;
        replanner.execute(() -> replan(blocked.planId(), blocked.revision()));
    }

    private void replan(String planId, long blockedRevision) {
        try {
            DurablePlan reserved = plans.reserveReplan(planId, blockedRevision);
            DurablePlan.DurableStep failedStep = reserved.steps().get(reserved.currentStep());
            AgentContext context = context(reserved, failedStep);
            HybridAgentPlanner.PlanningResult result = providers.replan(reserved.requestText(), context);
            DurablePlan updated;
            if (!result.accepted()) {
                updated = plans.recordReplanStop(reserved.planId(), reserved.revision(), result.decision(),
                        failedStep.observation(), result.errorCode());
            } else if (result.decision().kind() == DecisionKind.REPLAN) {
                updated = plans.applyReplan(reserved.planId(), reserved.revision(), result.decision(),
                        failedStep.observation(), failedStep.failureCode());
            } else if (result.decision().kind() == DecisionKind.CANCEL) {
                updated = plans.transitionStep(reserved.planId(), reserved.revision(), failedStep.index(),
                        StepState.CANCELLED, failedStep.observation(), "REPLAN_CANCELLED");
            } else {
                updated = plans.recordReplanStop(reserved.planId(), reserved.revision(), result.decision(),
                        failedStep.observation(), failedStep.failureCode());
            }
            if (updated.state() == StepState.READY) {
                synchronized (this) {
                    DurablePlan current = plans.get(updated.planId()).orElseThrow();
                    if (current.state() == StepState.READY && current.revision() == updated.revision()) {
                        DurablePlan dispatched = dispatch(current);
                        if (dispatched.state() == StepState.BLOCKED) scheduleReplan(dispatched);
                    }
                }
            }
        } catch (IllegalStateException staleOrBudgeted) {
            log.warn("Agent replan skipped: plan=" + planId + ", code=" + staleOrBudgeted.getMessage());
        } catch (SQLException | RuntimeException failure) {
            log.error("Agent plan could not replan from observation: plan=" + planId, failure);
        }
    }

    private AgentContext context(DurablePlan plan, DurablePlan.DurableStep failedStep) throws SQLException {
        CompanionRecord companion = companions.get(plan.companionId()).orElse(null);
        JsonNode status = companion == null ? Json.object() : companion.status();
        JsonNode verifiedWorld = memories == null ? status : memories.enrichVerifiedWorld(plan.companionId(), status);
        var session = sessions.forCompanion(plan.companionId()).orElse(null);
        List<String> landmarks = new ArrayList<>();
        status.path("knownLandmarks").forEach(value -> {
            if (value.isTextual() && landmarks.size() < 64) landmarks.add(value.asText());
        });
        var active = Json.object()
                .put("planId", plan.planId())
                .put("originalRequest", plan.requestText())
                .put("planningRevision", plan.planningRevision())
                .put("replanCount", plan.replanCount())
                .put("maxReplans", AgentPlanRepository.MAX_REPLANS)
                .put("noProgressCount", plan.noProgressCount())
                .put("failedStepIndex", failedStep.index())
                .put("failureCode", failedStep.failureCode() == null ? "BLOCKED" : failedStep.failureCode());
        active.set("failedStep", Json.MAPPER.valueToTree(failedStep.definition()));
        active.set("triggerObservation", failedStep.observation());
        active.put("instruction", "Revise the remaining short-horizon plan from this verified observation; do not claim success.");
        List<String> available = capabilityVisibility.resolve(
                session == null ? null : session.handshake(), status).availableNames();
        if (memories != null) landmarks.addAll(memories.verifiedLandmarkKeys(plan.companionId()));
        return new AgentContext(plan.companionId(), verifiedWorld, List.of(), active, landmarks, available, 5);
    }

    @Override public void close() {
        replanner.shutdownNow();
    }
}
