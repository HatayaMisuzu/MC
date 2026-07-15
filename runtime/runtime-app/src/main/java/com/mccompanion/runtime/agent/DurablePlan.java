package com.mccompanion.runtime.agent;

import java.time.Instant;
import java.util.List;

public record DurablePlan(String planId, String companionId, String requestText, AgentDecision decision,
                          StepState state, long revision, int currentStep, List<DurableStep> steps,
                          Instant createdAt, Instant updatedAt) {
    public record DurableStep(int index, PlanStep definition, StepState state, int attempt,
                              String taskId, String failureCode, com.fasterxml.jackson.databind.JsonNode observation) { }
}
