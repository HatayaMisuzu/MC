package com.mccompanion.runtime.brain;

import java.time.Instant;

public record BrainSession(String sessionId, String controllerId, String companionId, Instant openedAt) { }
