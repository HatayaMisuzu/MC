package com.mccompanion.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.command.CommandReply;
import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.capability.CapabilityVisibility;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.memory.MemoryRepository;
import com.mccompanion.runtime.conversation.ConversationOption;
import com.mccompanion.runtime.conversation.ConversationService;
import com.mccompanion.runtime.conversation.FailureAssessment;
import com.mccompanion.runtime.conversation.FailureClassifier;
import com.mccompanion.runtime.conversation.IncomingMessageResolution;
import com.mccompanion.runtime.conversation.WaitingQuestion;
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
    private final ConversationService conversations;
    private final FailureClassifier failureClassifier;
    private final ExecutorService replanner;
    private final CapabilityIntentTranslator translator = new CapabilityIntentTranslator();

    public AgentKernel(AgentPlanRepository plans, CommandService commands, RuntimeLog log) {
        this(plans, commands, log, null, null, null, null, null, null);
    }

    public AgentKernel(AgentPlanRepository plans, CommandService commands, RuntimeLog log,
                       ProviderRouter providers, CompanionRepository companions, SessionRegistry sessions,
                       CapabilityVisibility capabilityVisibility) {
        this(plans, commands, log, providers, companions, sessions, capabilityVisibility, null, null);
    }

    public AgentKernel(AgentPlanRepository plans, CommandService commands, RuntimeLog log,
                       ProviderRouter providers, CompanionRepository companions, SessionRegistry sessions,
                       CapabilityVisibility capabilityVisibility, MemoryRepository memories,
                       ConversationService conversations) {
        this.plans = plans; this.commands = commands; this.log = log; this.providers = providers;
        this.companions = companions; this.sessions = sessions; this.capabilityVisibility = capabilityVisibility;
        this.memories = memories;
        this.conversations = conversations;
        this.failureClassifier = new FailureClassifier();
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

    /** Applies an owner answer to the plan that asked the question; it never creates a competing plan. */
    public synchronized DurablePlan resumeWaitingAnswer(WaitingQuestion question,
                                                         IncomingMessageResolution answer) throws SQLException {
        if (conversations == null) throw new IllegalStateException("CONVERSATION_SERVICE_UNAVAILABLE");
        WaitingQuestion activeQuestion = conversations.repository().activeForCompanion(question.companionId())
                .filter(value -> value.questionId().equals(question.questionId()))
                .orElseThrow(() -> new IllegalStateException("QUESTION_NOT_WAITING"));
        DurablePlan plan = plans.get(activeQuestion.planId()).orElseThrow(
                () -> new IllegalStateException("WAITING_PLAN_NOT_FOUND"));
        if (!"WAITING_FOR_USER".equals(plan.interactionState()) || plan.state() != StepState.BLOCKED) {
            throw new IllegalStateException("PLAN_NOT_WAITING_FOR_USER");
        }
        DurablePlan.DurableStep failedStep = plan.steps().get(plan.currentStep());
        AgentDecision revision;
        if ("deliver_partial".equals(answer.optionId())) {
            revision = partialDeliveryRevision(plan, failedStep, activeQuestion);
        } else {
            AgentContext base = context(plan, failedStep);
            JsonNode contextNode = base.activeTask().deepCopy();
            if (!(contextNode instanceof com.fasterxml.jackson.databind.node.ObjectNode active)) {
                throw new IllegalStateException("INVALID_ACTIVE_TASK_CONTEXT");
            }
            active.put("waitingQuestionId", activeQuestion.questionId())
                    .put("ownerAnswer", answer.text())
                    .put("selectedOption", answer.optionId() == null ? "free_text" : answer.optionId())
                    .put("instruction", "Continue the original plan from verified progress. Apply the owner's answer and do not repeat completed work.");
            active.set("waitingQuestionContext", activeQuestion.context());
            var result = providers.replan(plan.requestText(), new AgentContext(base.companionId(),
                    base.verifiedWorld(), base.recentConversation(), active, base.knownLandmarks(),
                    base.availableCapabilities(), base.preferences(), base.maxPlanSteps()));
            if (!result.accepted() || result.decision().kind() != DecisionKind.REPLAN
                    || result.decision().steps().isEmpty()) {
                throw new IllegalStateException(result.errorCode() == null ? "ANSWER_REPLAN_REJECTED" : result.errorCode());
            }
            revision = result.decision();
        }

        conversations.repository().answer(activeQuestion.questionId(), answer.text(), answer.optionId());
        var answerObservation = failedStep.observation().isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) failedStep.observation().deepCopy() : Json.object();
        answerObservation.put("questionId", activeQuestion.questionId())
                .put("ownerAnswer", answer.text())
                .put("selectedOption", answer.optionId() == null ? "free_text" : answer.optionId());
        DurablePlan queued = plans.queueGoalModification(plan.planId(), plan.revision(), plan.requestText(), revision,
                answerObservation);
        if (commands != null && commands.activeTaskFor(plan.companionId()).isPresent()) {
            CommandReply cancellation = commands.execute("answer-change-" + activeQuestion.questionId(),
                    plan.companionId(), new Intent(com.mccompanion.runtime.task.TaskType.STOP,
                            Json.object().put("action", "cancel"), answer.text()));
            if (!cancellation.accepted()) throw new IllegalStateException("WAITING_TASK_CANCEL_REJECTED");
            conversations.say(plan.companionId(), plan.planId(), "ANSWER_ACCEPTED",
                    "收到，我会按你的选择继续原来的任务。", Json.object()
                            .put("questionId", activeQuestion.questionId())
                            .put("selectedOption", answer.optionId() == null ? "free_text" : answer.optionId()));
            return queued;
        }
        DurablePlan activated = plans.activateGoalModification(queued.planId(), queued.revision(), failedStep.index(),
                StepState.FAILED, answerObservation, "RESUMED_FROM_USER_ANSWER");
        conversations.say(plan.companionId(), plan.planId(), "ANSWER_ACCEPTED",
                "收到，我会按你的选择继续原来的任务。", Json.object()
                        .put("questionId", activeQuestion.questionId())
                        .put("selectedOption", answer.optionId() == null ? "free_text" : answer.optionId()));
        return commands == null ? activated : start(activated.planId());
    }

    private static AgentDecision partialDeliveryRevision(DurablePlan plan, DurablePlan.DurableStep failedStep,
                                                          WaitingQuestion question) {
        int available = question.context().path("available").asInt(-1);
        if (available <= 0) throw new IllegalStateException("NO_PARTIAL_RESULT_AVAILABLE");
        List<PlanStep> remaining = new ArrayList<>();
        for (DurablePlan.DurableStep step : plan.steps()) {
            if (step.index() < failedStep.index() || step.state().terminal()) continue;
            PlanStep definition = step.definition();
            JsonNode parameters = definition.parameters().deepCopy();
            if (parameters instanceof com.fasterxml.jackson.databind.node.ObjectNode object && object.has("quantity")) {
                object.put("quantity", available);
            }
            remaining.add(new PlanStep(definition.goalState(), definition.capability(), parameters,
                    definition.expectedResult(), definition.completionCriteria(), definition.failurePolicy(),
                    definition.opportunistic(), definition.risk()));
        }
        if (remaining.isEmpty()) throw new IllegalStateException("NO_REMAINING_STEPS");
        List<String> constraints = new ArrayList<>(plan.decision().constraints());
        constraints.add("Owner accepted the verified partial quantity: " + available);
        return new AgentDecision(DecisionKind.REPLAN, plan.decision().understoodGoal(), constraints,
                plan.decision().assumptions(), remaining,
                "先交付已经确认可取得的 " + available + " 个。", "OWNER_ACCEPTED_PARTIAL_RESULT");
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
            DurablePlan.DurableStep step = plan.steps().stream()
                    .filter(value -> task.taskId().equals(value.taskId())).findFirst().orElse(null);
            if (step == null) return;
            StepState next = switch (task.state()) {
                case COMPLETED -> StepState.SUCCEEDED;
                case CANCELLED -> StepState.CANCELLED;
                case FAILED -> StepState.BLOCKED;
                default -> StepState.BLOCKED;
            };
            String failure = next == StepState.SUCCEEDED ? null : observation.path("code").asText(task.state().name());
            if (plan.state() == StepState.PAUSED && step.index() != plan.currentStep() && task.state().terminal()) {
                StepState oldTerminal = switch (task.state()) {
                    case COMPLETED -> StepState.SUCCEEDED;
                    case FAILED -> StepState.FAILED;
                    default -> StepState.CANCELLED;
                };
                DurablePlan activated = plans.activateGoalModification(plan.planId(), plan.revision(), step.index(),
                        oldTerminal, observation, "GOAL_MODIFIED_" + task.state().name());
                DurablePlan dispatched = dispatch(activated);
                if (dispatched.state() == StepState.BLOCKED) scheduleReplan(dispatched);
                return;
            }
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
            DurablePlan blocked = plans.get(planId).orElseThrow();
            if (blocked.revision() != blockedRevision || blocked.state() != StepState.BLOCKED) return;
            DurablePlan.DurableStep blockedStep = blocked.steps().get(blocked.currentStep());
            FailureAssessment assessment = failureClassifier.classify(blockedStep.failureCode(),
                    blocked.requestText(), blockedStep.observation(), blockedStep.definition());
            if (assessment.requiresUserChoice() && conversations != null) {
                DurablePlan waiting = plans.markWaitingForUser(blocked.planId(), blocked.revision());
                askUser(waiting, blockedStep, assessment);
                return;
            }
            if (!assessment.autonomousReplanAllowed() && conversations != null) {
                conversations.say(blocked.companionId(), blocked.planId(), "BLOCKED",
                        blockedMessage(assessment), Json.object().put("failureCategory", assessment.category().name())
                                .put("failureCode", blockedStep.failureCode()));
                return;
            }
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

    private void askUser(DurablePlan plan, DurablePlan.DurableStep failedStep,
                         FailureAssessment assessment) throws SQLException {
        if (askUserReadable(plan, failedStep, assessment)) return;
        int requested = failedStep.definition().parameters().path("quantity").asInt(-1);
        int available = observedInt(failedStep.observation(), "available", "availableQuantity", "actualQuantity");
        String prompt = assessment.category() == com.mccompanion.runtime.conversation.FailureCategory.RESOURCE_SHORTAGE
                && available >= 0 && requested > 0
                ? "这个来源里只有 " + available + " 个，目标是 " + requested + " 个，还差 "
                + Math.max(0, requested - available) + " 个。你想怎么做？"
                : "当前方法无法按原条件继续。你希望我怎么处理？";
        java.util.ArrayList<ConversationOption> options = new java.util.ArrayList<>();
        if (available > 0) options.add(new ConversationOption("deliver_partial", "先把现有的拿来", "返回并交付已验证数量"));
        options.add(new ConversationOption("search_other", "看看其他来源", "只检查其他已知合法来源"));
        options.add(new ConversationOption("collect_missing", "去补齐缺口", "授权扩大为资源采集任务"));
        if (options.size() > 3) options.removeLast();
        var context = Json.object().put("failureCategory", assessment.category().name())
                .put("failureCode", failedStep.failureCode()).put("requested", requested).put("available", available);
        context.set("observation", failedStep.observation());
        conversations.ask(plan.companionId(), plan.planId(), prompt, assessment.reason(), options, true, context);
    }

    private boolean askUserReadable(DurablePlan plan, DurablePlan.DurableStep failedStep,
                                    FailureAssessment assessment) throws SQLException {
        int requested = failedStep.definition().parameters().path("quantity").asInt(-1);
        int available = observedInt(failedStep.observation(), "available", "availableQuantity", "actualQuantity");
        String prompt = assessment.category() == com.mccompanion.runtime.conversation.FailureCategory.RESOURCE_SHORTAGE
                && available >= 0 && requested > 0
                ? "这个来源里只有 " + available + " 个，目标是 " + requested + " 个，还差 "
                + Math.max(0, requested - available) + " 个。你想怎么做？"
                : "当前方法无法按原条件继续。你希望我怎么处理？";
        List<ConversationOption> options = new ArrayList<>();
        if (available > 0) options.add(new ConversationOption("deliver_partial", "先把现有的拿来", "返回并交付已验证数量"));
        options.add(new ConversationOption("search_other", "看看其他来源", "只检查其他已知合法来源"));
        options.add(new ConversationOption("collect_missing", "去补齐缺口", "授权扩大为资源采集任务"));
        if (options.size() > 3) options.removeLast();
        var questionContext = Json.object().put("failureCategory", assessment.category().name())
                .put("failureCode", failedStep.failureCode()).put("requested", requested).put("available", available);
        questionContext.set("observation", failedStep.observation());
        conversations.ask(plan.companionId(), plan.planId(), prompt, assessment.reason(), options, true, questionContext);
        return true;
    }

    private static int observedInt(JsonNode observation, String... names) {
        for (String name : names) if (observation.path(name).canConvertToInt()) return observation.path(name).asInt();
        JsonNode snapshot = observation.path("snapshot");
        for (String name : names) if (snapshot.path(name).canConvertToInt()) return snapshot.path(name).asInt();
        return -1;
    }

    private static String blockedMessage(FailureAssessment assessment) {
        if (assessment != null) return switch (assessment.category()) {
            case UNSUPPORTED_CAPABILITY -> "我现在还没有可靠完成这一步的身体能力，先停在这里，没有尝试替代或作弊动作。";
            case SAFETY_BLOCKED -> "现在继续不安全，我已经停下并保留了任务。";
            case EXTERNAL_SERVICE_UNAVAILABLE -> "规划服务暂时不可用，任务和现场状态都已保留。";
            default -> "这条路径暂时无法可靠继续，我已经停下并保留了实际进度。";
        };
        return switch (assessment.category()) {
            case UNSUPPORTED_CAPABILITY -> "我现在还没有可靠完成这一步的身体能力，先停在这里，没有尝试作弊替代。";
            case SAFETY_BLOCKED -> "现在继续不安全，我已经停下并保留了任务。";
            case EXTERNAL_SERVICE_UNAVAILABLE -> "规划服务暂时不可用，任务和现场状态都已保留。";
            default -> "这条路暂时无法可靠继续，我已经停下并保留了实际进度。";
        };
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
        List<String> recent = List.of();
        if (conversations != null) {
            recent = conversations.repository().list(plan.companionId(), 12).stream()
                    .map(event -> event.direction() + ": " + event.content()).toList();
        }
        JsonNode preferences = memories == null ? Json.MAPPER.createArrayNode()
                : memories.preferenceContext(plan.companionId(), 24);
        return new AgentContext(plan.companionId(), verifiedWorld, recent, active, landmarks, available,
                preferences, 5);
    }

    @Override public void close() {
        replanner.shutdownNow();
        try {
            if (!replanner.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("Agent replanner did not stop within five seconds");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
