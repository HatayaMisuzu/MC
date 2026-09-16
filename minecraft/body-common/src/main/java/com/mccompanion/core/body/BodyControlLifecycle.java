package com.mccompanion.core.body;

import java.util.Optional;
import java.util.UUID;

/** Shared ownership lifecycle used by every Minecraft version binding. */
public final class BodyControlLifecycle {
    private final BodyControlArbiter arbiter = new BodyControlArbiter();

    public BodyControlArbiter.Decision claim(UUID companionId, BodyControlArbiter.Authority authority,
                                             String ownerIdentity, String reason) {
        return arbiter.claim(companionId, authority, ownerIdentity, reason);
    }

    public Optional<BodyControlArbiter.Decision> release(UUID companionId,
            BodyControlArbiter.Authority authority, String ownerIdentity, String reason) {
        BodyControlArbiter.Snapshot snapshot = arbiter.snapshot(companionId);
        if (snapshot.authority() != authority || !snapshot.ownerIdentity().equals(ownerIdentity)) {
            return Optional.empty();
        }
        return Optional.of(arbiter.release(companionId, authority, snapshot.claimToken(), reason));
    }

    public Optional<BodyControlArbiter.Decision> releaseCurrent(UUID companionId,
            String protectedOwnerIdentity, String reason) {
        BodyControlArbiter.Snapshot snapshot = arbiter.snapshot(companionId);
        if (snapshot.authority() == BodyControlArbiter.Authority.OWNER_IMMEDIATE
                && !snapshot.ownerIdentity().equals(protectedOwnerIdentity)) {
            return Optional.empty();
        }
        return Optional.of(arbiter.releaseCurrent(companionId, snapshot.claimToken(), reason));
    }

    public BodyControlArbiter.Snapshot snapshot(UUID companionId) {
        return arbiter.snapshot(companionId);
    }

    public void clear(UUID companionId) {
        arbiter.clear(companionId);
    }

    public static String runtimeIdentity(long epoch, String behaviorId, long behaviorRevision) {
        return "runtime:" + epoch + ":" + (behaviorId == null ? "" : behaviorId)
                + ":" + behaviorRevision;
    }

    public static boolean isSuspension(String code) {
        return code != null && (code.equals("LOCAL_THREAT_HANDLING") || code.startsWith("RECOVERY_")
                || code.equals("THREAT_RECOVERY_TIMEOUT") || code.equals("RUNTIME_PAUSE")
                || code.equals("RUNTIME_DISCONNECTED") || code.equals("RUNTIME_OFFLINE")
                || code.equals("LEASE_EXPIRED") || code.equals("PAUSED_BY_OWNER")
                || code.equals("LOW_HEALTH") || code.equals("ENVIRONMENT_HAZARD")
                || code.equals("DROWNING_RISK"));
    }
}
