package com.mccompanion.runtime.provider;

import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.intent.RuleIntentParser;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.agent.HybridAgentPlanner;

import java.util.Optional;

public final class ProviderRouter {
    private final RuleIntentParser rules;
    private final IntentProvider provider;
    private final RuntimeLog log;
    private final HybridAgentPlanner planner;

    public ProviderRouter(RuleIntentParser rules, IntentProvider provider, RuntimeLog log) {
        this.rules = rules;
        this.provider = provider;
        this.log = log;
        this.planner = new HybridAgentPlanner(provider instanceof DecisionProvider decisions ? decisions : null);
    }

    public HybridAgentPlanner.PlanningResult plan(String text, AgentContext context) {
        HybridAgentPlanner.PlanningResult result = planner.decide(text, context);
        if (!result.accepted()) log.warn("Agent planning stopped safely: code=" + result.errorCode());
        return result;
    }

    public HybridAgentPlanner.PlanningResult replan(String originalRequest, AgentContext context) {
        HybridAgentPlanner.PlanningResult result = planner.replan(originalRequest, context);
        if (!result.accepted()) log.warn("Agent replanning stopped safely: code=" + result.errorCode());
        return result;
    }

    public Resolution resolve(String text) {
        Optional<Intent> rule = rules.parse(text);
        if (rule.isPresent()) {
            return new Resolution(rule, "rules", false, null, null);
        }
        if (provider == null) {
            return new Resolution(Optional.empty(), "rules", false, "UNKNOWN_COMMAND",
                    "无法识别命令。输入 help 查看可用命令。");
        }
        try {
            return new Resolution(Optional.of(provider.parse(text)), "provider", false, null, null);
        } catch (ProviderException failure) {
            log.warn("Provider intent resolution failed; falling back to rules: code=" + failure.code());
            Optional<Intent> fallback = rules.parse(text);
            return new Resolution(fallback, "rules", true, failure.code(),
                    fallback.isPresent() ? null : "模型暂时不可用，且规则模式无法识别该命令。");
        }
    }

    public record Resolution(Optional<Intent> intent, String source, boolean fallbackUsed,
                             String errorCode, String userMessage) {
    }
}
