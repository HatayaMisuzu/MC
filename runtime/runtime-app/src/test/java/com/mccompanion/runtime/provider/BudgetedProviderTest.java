package com.mccompanion.runtime.provider;

import com.mccompanion.runtime.agent.AgentDecision;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.task.TaskType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class BudgetedProviderTest {
    @Test
    void retriesOnlyRetryableFailuresWithinCallBudget() throws Exception {
        FakeProvider fake = new FakeProvider(2);
        BudgetedProvider provider = new BudgetedProvider(fake, fake, 1, 3, 2,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        assertEquals(TaskType.STATUS, provider.parse("status").type());
        assertEquals(3, fake.calls);
        ProviderException budget = assertThrows(ProviderException.class, () -> provider.parse("again"));
        assertEquals("PROVIDER_BUDGET_EXCEEDED", budget.code());
    }

    @Test
    void interruptedRequestIsCancelledBeforeNetworkCall() {
        FakeProvider fake = new FakeProvider(0);
        BudgetedProvider provider = new BudgetedProvider(fake, fake, 1, 10, 0, Clock.systemUTC());
        Thread.currentThread().interrupt();
        try {
            ProviderException cancelled = assertThrows(ProviderException.class, () -> provider.parse("status"));
            assertEquals("PROVIDER_CANCELLED", cancelled.code());
            assertEquals(0, fake.calls);
        } finally { Thread.interrupted(); }
    }

    private static final class FakeProvider implements IntentProvider, DecisionProvider {
        private int failures;
        private int calls;
        FakeProvider(int failures) { this.failures = failures; }
        @Override public Intent parse(String userText) throws ProviderException {
            calls++;
            if (failures-- > 0) throw new ProviderException("PROVIDER_RETRYABLE", "retry");
            return new Intent(TaskType.STATUS, Json.object(), userText);
        }
        @Override public AgentDecision decide(AgentRequest request) { return AgentDecision.respond("ok"); }
    }
}
