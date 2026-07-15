package com.mccompanion.runtime.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.capability.CapabilityRegistry;
import com.mccompanion.runtime.input.HintExtractor;
import com.mccompanion.runtime.input.NormalizedInput;
import com.mccompanion.runtime.input.TextNormalizer;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.intent.RuleIntentParser;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.provider.AgentRequest;
import com.mccompanion.runtime.provider.DecisionProvider;
import com.mccompanion.runtime.provider.ProviderException;
import com.mccompanion.runtime.task.TaskType;

import java.util.List;
import java.util.Optional;

/** Safety/control fast path + hints + context-aware model + deterministic validation. */
public final class HybridAgentPlanner {
    private final TextNormalizer normalizer;
    private final RuleIntentParser fastPath;
    private final HintExtractor hints;
    private final DecisionProvider provider;
    private final DecisionValidator validator;
    private final CapabilityRegistry capabilities;

    public HybridAgentPlanner(DecisionProvider provider) {
        this(new TextNormalizer(), new RuleIntentParser(), new HintExtractor(), provider, CapabilityRegistry.standard());
    }

    HybridAgentPlanner(TextNormalizer normalizer, RuleIntentParser fastPath, HintExtractor hints,
                       DecisionProvider provider, CapabilityRegistry capabilities) {
        this.normalizer = normalizer;
        this.fastPath = fastPath;
        this.hints = hints;
        this.provider = provider;
        this.capabilities = capabilities;
        this.validator = new DecisionValidator(capabilities);
    }

    public PlanningResult decide(String text, AgentContext suppliedContext) {
        final NormalizedInput input;
        try { input = normalizer.normalize(text); }
        catch (IllegalArgumentException invalid) { return PlanningResult.rejected("INVALID_REQUEST", invalid.getMessage()); }
        if (input.normalized().isBlank()) return PlanningResult.rejected("INVALID_REQUEST", "请输入你希望伙伴做什么。");
        Optional<Intent> fast = fastPath.parse(input.normalized());
        if (fast.isPresent()) return new PlanningResult(controlDecision(fast.get()), fast, "fast_path", null, null);
        if (provider == null) return PlanningResult.rejected("PROVIDER_UNAVAILABLE", "模型尚未配置；暂停复杂任务，状态与暂停/继续/取消仍可用。");
        AgentContext context = suppliedContext == null
                ? AgentContext.empty("", capabilities.names()) : suppliedContext;
        try {
            AgentDecision decision = provider.decide(new AgentRequest(input, hints.extract(input), context));
            DecisionValidator.Validation validation = validator.validate(decision, context);
            if (!validation.valid()) return PlanningResult.rejected("DECISION_REJECTED", String.join("; ", validation.errors()));
            return new PlanningResult(decision, Optional.empty(), "provider", null, null);
        } catch (ProviderException failure) {
            return PlanningResult.rejected(failure.code(), "模型暂时不可用；复杂任务已安全暂停，你可以稍后重试或取消。");
        }
    }

    /** Provider-only revision path: observations must not be mistaken for a fresh fast-path command. */
    public PlanningResult replan(String originalRequest, AgentContext suppliedContext) {
        final NormalizedInput input;
        try { input = normalizer.normalize(originalRequest); }
        catch (IllegalArgumentException invalid) { return PlanningResult.rejected("INVALID_REQUEST", invalid.getMessage()); }
        if (provider == null) return PlanningResult.rejected("PROVIDER_UNAVAILABLE",
                "模型尚未配置；任务保持阻塞，未执行替代动作。");
        AgentContext context = suppliedContext == null
                ? AgentContext.empty("", capabilities.names()) : suppliedContext;
        try {
            AgentDecision decision = provider.decide(new AgentRequest(input, hints.extract(input), context));
            if (decision.kind() != DecisionKind.REPLAN
                    && decision.kind() != DecisionKind.ASK_CLARIFICATION
                    && decision.kind() != DecisionKind.REPORT_BLOCKED
                    && decision.kind() != DecisionKind.PAUSE
                    && decision.kind() != DecisionKind.CANCEL) {
                return PlanningResult.rejected("REPLAN_DECISION_REQUIRED",
                        "模型未返回可执行的重规划决定；任务保持阻塞。");
            }
            DecisionValidator.Validation validation = validator.validate(decision, context);
            if (!validation.valid()) return PlanningResult.rejected("DECISION_REJECTED", String.join("; ", validation.errors()));
            return new PlanningResult(decision, Optional.empty(), "provider_replan", null, null);
        } catch (ProviderException failure) {
            return PlanningResult.rejected(failure.code(), "模型暂时不可用；任务保持阻塞，可稍后重试或取消。");
        }
    }

    private static AgentDecision controlDecision(Intent intent) {
        DecisionKind kind = switch (intent.type()) {
            case STOP -> switch (intent.arguments().path("action").asText("cancel")) {
                case "pause" -> DecisionKind.PAUSE;
                case "resume" -> DecisionKind.RESUME;
                default -> DecisionKind.CANCEL;
            };
            case STATUS -> DecisionKind.RESPOND;
            case SKILL -> DecisionKind.CREATE_PLAN;
            default -> DecisionKind.CREATE_PLAN;
        };
        List<PlanStep> steps = switch (intent.type()) {
            case FOLLOW -> List.of(step("FollowOwner", Json.object()));
            case RETURN -> List.of(step("NavigateTo", Json.object().put("target", "owner")));
            case TRAVEL -> List.of(step("NavigateTo", (ObjectNode) intent.arguments().deepCopy()));
            case SKILL -> List.of();
            default -> List.of();
        };
        String reply = switch (kind) {
            case PAUSE -> "好的，我先停在安全位置。";
            case RESUME -> "继续刚才的任务。";
            case CANCEL -> "已取消当前任务。";
            case RESPOND -> "我来检查当前状态。";
            default -> "明白，我会按这个目标行动。";
        };
        return new AgentDecision(kind, intent.originalText(), List.of(), List.of(), steps, reply, "fast_path");
    }

    private static PlanStep step(String capability, ObjectNode parameters) {
        return new PlanStep("到达目标状态", capability, parameters, "到达目标",
                Json.object().put("positionVerified", true), "不可达时停止并报告", false, RiskLevel.LOW);
    }

    public record PlanningResult(AgentDecision decision, Optional<Intent> executableIntent, String source,
                                 String errorCode, String userMessage) {
        static PlanningResult rejected(String code, String message) {
            return new PlanningResult(AgentDecision.clarify("", message, code), Optional.empty(), "safe_fallback", code, message);
        }
        public boolean accepted() { return errorCode == null; }
    }
}
