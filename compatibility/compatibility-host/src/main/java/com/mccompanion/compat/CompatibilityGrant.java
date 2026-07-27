package com.mccompanion.compat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/** User-issued scope for a development controller; never issued to a runtime game Brain. */
public record CompatibilityGrant(
        String grantId,
        String agentId,
        String controllerId,
        String profileId,
        String instanceId,
        Path storeRoot,
        Set<String> operations,
        Instant expiresAt,
        CompatibilityPack.Risk maximumRisk) {

    public static final Set<String> KNOWN_OPERATIONS = Set.of(
            "compat.list", "compat.inspect", "compat.diagnose", "compat.install",
            "compat.record_evidence", "compat.index", "compat.activate", "compat.deactivate",
            "compat.update", "compat.patch", "compat.rollback", "compat.remove",
            "compat.quarantine", "compat.export");

    public CompatibilityGrant {
        grantId = CompatibilityPack.identifier(grantId, "grantId");
        agentId = CompatibilityPack.identifier(agentId, "agentId");
        controllerId = CompatibilityPack.identifier(controllerId, "controllerId");
        profileId = scopeId(profileId);
        instanceId = scopeId(instanceId);
        storeRoot = storeRoot.toAbsolutePath().normalize();
        operations = Set.copyOf(operations == null ? Set.of() : operations);
        if (operations.isEmpty() || !KNOWN_OPERATIONS.containsAll(operations)) {
            throw new IllegalArgumentException("INVALID_COMPATIBILITY_OPERATIONS");
        }
        expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt");
        maximumRisk = java.util.Objects.requireNonNull(maximumRisk, "maximumRisk");
    }

    public void require(String operation, Path actualStore, String actualProfile,
                        String actualInstance, CompatibilityPack.Risk risk, Clock clock) {
        if (!KNOWN_OPERATIONS.contains(operation) || !operations.contains(operation)) {
            throw new SecurityException("COMPAT_OPERATION_NOT_AUTHORIZED");
        }
        if (!storeRoot.equals(actualStore.toAbsolutePath().normalize())
                || !profileId.equals(actualProfile) || !instanceId.equals(actualInstance)) {
            throw new SecurityException("COMPAT_SCOPE_MISMATCH");
        }
        if (!clock.instant().isBefore(expiresAt)) throw new SecurityException("COMPAT_GRANT_EXPIRED");
        if (!maximumRisk.allows(risk)) throw new SecurityException("COMPAT_RISK_NOT_AUTHORIZED");
    }

    static String scopeId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("INVALID_COMPAT_SCOPE_ID");
        }
        return value;
    }
}
