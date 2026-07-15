package com.mccompanion.runtime.agent;

import java.util.List;

public record AgentDecision(
        DecisionKind kind,
        String understoodGoal,
        List<String> constraints,
        List<String> assumptions,
        List<PlanStep> steps,
        String reply,
        String reason) {
    public AgentDecision {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        understoodGoal = clean(understoodGoal);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        steps = steps == null ? List.of() : List.copyOf(steps);
        reply = clean(reply);
        reason = clean(reason);
    }

    public static AgentDecision respond(String reply) {
        return new AgentDecision(DecisionKind.RESPOND, "", List.of(), List.of(), List.of(), reply, "");
    }

    public static AgentDecision clarify(String goal, String reply, String reason) {
        return new AgentDecision(DecisionKind.ASK_CLARIFICATION, goal, List.of(), List.of(), List.of(), reply, reason);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
