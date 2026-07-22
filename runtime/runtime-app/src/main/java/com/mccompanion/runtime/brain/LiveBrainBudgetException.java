package com.mccompanion.runtime.brain;

public final class LiveBrainBudgetException extends IllegalStateException {
    private final LiveBrainFailureCategory category;

    public LiveBrainBudgetException(String code, LiveBrainFailureCategory category) {
        super(code);
        this.category = category;
    }

    public LiveBrainFailureCategory category() { return category; }
}
