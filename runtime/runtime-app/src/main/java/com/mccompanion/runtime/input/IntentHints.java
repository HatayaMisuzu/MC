package com.mccompanion.runtime.input;

import java.util.List;

public record IntentHints(String possibleIntent, double confidence, List<EntityCandidate> items, boolean deliveryLikely) {
    public record EntityCandidate(String id, double confidence) { }
}
