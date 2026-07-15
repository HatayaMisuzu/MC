package com.mccompanion.runtime.agent;

import com.mccompanion.runtime.capability.CapabilityDefinition;
import com.mccompanion.runtime.capability.CapabilityRegistry;

import java.util.ArrayList;
import java.util.List;

/** Treats model output as untrusted: schema is only the first validation layer. */
public final class DecisionValidator {
    private final CapabilityRegistry capabilities;

    public DecisionValidator(CapabilityRegistry capabilities) { this.capabilities = capabilities; }

    public Validation validate(AgentDecision decision, AgentContext context) {
        List<String> errors = new ArrayList<>();
        if (decision.reply().length() > 1000) errors.add("reply exceeds 1000 characters");
        if (decision.kind() == DecisionKind.CREATE_PLAN || decision.kind() == DecisionKind.REPLAN) {
            if (decision.understoodGoal().isBlank()) errors.add("planned decision has no understood goal");
            if (decision.steps().isEmpty()) errors.add("planned decision has no steps");
            if (decision.steps().size() > context.maxPlanSteps()) errors.add("plan exceeds short-horizon budget");
        } else if (!decision.steps().isEmpty()) {
            errors.add("non-plan decision must not contain steps");
        }
        for (PlanStep step : decision.steps()) {
            CapabilityDefinition capability = capabilities.find(step.capability()).orElse(null);
            if (capability == null || !context.availableCapabilities().contains(step.capability())) {
                errors.add("capability is unavailable: " + step.capability());
                continue;
            }
            if (step.risk().ordinal() < capability.risk().ordinal()) errors.add("risk is understated: " + step.capability());
            if (step.risk() == RiskLevel.HIGH && decision.constraints().stream().noneMatch(v -> v.contains("授权"))) {
                errors.add("high-risk step requires explicit authorization constraint");
            }
            if (step.parameters().has("script") || step.parameters().has("command") || step.parameters().has("setBlock")) {
                errors.add("arbitrary execution or world mutation parameter is forbidden");
            }
        }
        if (decision.kind() == DecisionKind.COMPLETE_CANDIDATE && decision.reason().isBlank()) {
            errors.add("completion candidate requires validator evidence reference");
        }
        return new Validation(errors.isEmpty(), List.copyOf(errors));
    }

    public record Validation(boolean valid, List<String> errors) { }
}
