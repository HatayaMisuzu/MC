package com.mccompanion.runtime.brain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Explicit deterministic external Brain used by automated tests; never reported as Live. */
public final class ReplayBrainAdapter implements ExternalBrainAdapter {
    private final Function<BrainTurnRequest, BrainTurnResult> script;
    private final Map<String, BrainSession> sessions = new ConcurrentHashMap<>();

    public ReplayBrainAdapter(Function<BrainTurnRequest, BrainTurnResult> script) {
        this.script = java.util.Objects.requireNonNull(script, "script");
    }

    @Override public BrainSession openSession(BrainSessionRequest request) {
        BrainSession session = new BrainSession(UUID.randomUUID().toString(), request.controllerId(),
                request.companionId(), Instant.now());
        sessions.put(session.sessionId(), session);
        return session;
    }

    @Override public boolean supportsResume() { return true; }

    @Override public BrainSession resumeSession(BrainSessionRequest request, String sessionId) {
        BrainSession session = new BrainSession(sessionId, request.controllerId(), request.companionId(), Instant.now());
        sessions.put(sessionId, session);
        return session;
    }

    @Override public BrainTurnResult continueTurn(BrainTurnRequest request) {
        if (!sessions.containsKey(request.sessionId())) throw new IllegalStateException("BRAIN_SESSION_NOT_FOUND");
        return script.apply(request);
    }

    @Override public void cancel(String sessionId, String reason) { sessions.remove(sessionId); }
    @Override public BrainHealth health() { return BrainHealth.ready("replay"); }
    public int sessionCount() { return sessions.size(); }
}
