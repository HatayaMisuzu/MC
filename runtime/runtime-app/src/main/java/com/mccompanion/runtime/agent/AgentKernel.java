package com.mccompanion.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.command.CommandReply;
import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.task.TaskRecord;
import com.mccompanion.runtime.task.TaskState;

import java.sql.SQLException;
import java.util.UUID;

/** Executes one reusable capability at a time and advances only from deterministic task observations. */
public final class AgentKernel implements CommandService.TaskLifecycleListener {
    private final AgentPlanRepository plans;
    private final CommandService commands;
    private final RuntimeLog log;
    private final CapabilityIntentTranslator translator = new CapabilityIntentTranslator();

    public AgentKernel(AgentPlanRepository plans, CommandService commands, RuntimeLog log) {
        this.plans = plans; this.commands = commands; this.log = log;
    }

    public synchronized DurablePlan start(String planId) throws SQLException {
        DurablePlan plan = plans.get(planId).orElseThrow(() -> new IllegalArgumentException("PLAN_NOT_FOUND"));
        if (plan.state() != StepState.READY) return plan;
        return dispatch(plan);
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
                case FAILED -> StepState.FAILED;
                default -> StepState.BLOCKED;
            };
            String failure = next == StepState.SUCCEEDED ? null : observation.path("code").asText(task.state().name());
            DurablePlan updated = plans.transitionStep(plan.planId(), plan.revision(), step.index(), next, observation, failure);
            if (updated.state() == StepState.READY) dispatch(updated);
        } catch (SQLException | RuntimeException failure) {
            log.error("Agent plan could not apply task observation: task=" + task.taskId(), failure);
        }
    }
}
