package com.mccompanion.runtime.brain;

public interface ExternalBrainAdapter extends AutoCloseable {
    BrainSession openSession(BrainSessionRequest request);
    default boolean supportsResume() { return false; }
    default BrainSession resumeSession(BrainSessionRequest request, String sessionId) {
        throw new UnsupportedOperationException("BRAIN_RESUME_UNSUPPORTED");
    }
    BrainTurnResult continueTurn(BrainTurnRequest request);
    void cancel(String sessionId, String reason);
    BrainHealth health();
    @Override default void close() { }
}
