package com.mccompanion.runtime.provider;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.input.IntentHints;
import com.mccompanion.runtime.input.NormalizedInput;

public record AgentRequest(NormalizedInput input, IntentHints hints, AgentContext context) {
    public AgentRequest {
        if (input == null || hints == null || context == null) throw new IllegalArgumentException("Agent request fields are required");
    }
}
