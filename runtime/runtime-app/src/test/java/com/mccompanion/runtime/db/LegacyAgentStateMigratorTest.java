package com.mccompanion.runtime.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mccompanion.runtime.agent.AgentDecision;
import com.mccompanion.runtime.agent.AgentPlanRepository;
import com.mccompanion.runtime.agent.DecisionKind;
import com.mccompanion.runtime.agent.PlanStep;
import com.mccompanion.runtime.agent.RiskLevel;
import com.mccompanion.runtime.agent.StepState;
import com.mccompanion.runtime.json.Json;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyAgentStateMigratorTest {
  @TempDir Path temporary;

  @Test
  void pausesOldRunningStateWithExplicitRemovalReason() throws Exception {
    try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("legacy.db"))) {
      database.initialize();
      AgentPlanRepository repository = new AgentPlanRepository(database);
      AgentDecision decision = new AgentDecision(DecisionKind.CREATE_PLAN, "legacy", List.of(), List.of(),
          List.of(new PlanStep("legacy", "FollowOwner", Json.object(), "verified",
              Json.object().put("verified", true), "pause", false, RiskLevel.LOW)), "", "fixture");
      var plan = repository.create("c1", "legacy request", decision);
      plan = repository.transitionStep(plan.planId(), plan.revision(), 0, StepState.RUNNING,
          Json.object(), null);

      assertEquals(1, new LegacyAgentStateMigrator(database).pauseRunningForExternalBrainMigration());
      var migrated = repository.get(plan.planId()).orElseThrow();
      assertEquals(StepState.PAUSED, migrated.state());
      assertEquals("INTERNAL_AGENT_REMOVED", migrated.steps().getFirst().failureCode());
    }
  }
}
