package com.mccompanion.runtime.provider;

import com.mccompanion.runtime.agent.AgentDecision;

public interface DecisionProvider {
    AgentDecision decide(AgentRequest request) throws ProviderException;
}
