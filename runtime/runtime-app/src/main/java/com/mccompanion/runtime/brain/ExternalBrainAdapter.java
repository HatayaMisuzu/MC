package com.mccompanion.runtime.brain;

public interface ExternalBrainAdapter extends AutoCloseable {
    BrainSession openSession(BrainSessionRequest request);
    BrainTurnResult continueTurn(BrainTurnRequest request);
    void cancel(String sessionId, String reason);
    BrainHealth health();
    @Override default void close() { }
}
