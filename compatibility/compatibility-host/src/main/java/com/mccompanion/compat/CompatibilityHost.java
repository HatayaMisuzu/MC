package com.mccompanion.compat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic compatibility management facade.
 *
 * <p>It validates and applies caller-selected operations; it never chooses a product goal,
 * invents an adaptation, compiles code, or grants a runtime Brain development authority.</p>
 */
public final class CompatibilityHost {
    private final CompatibilityStore store;
    private final String profileId;
    private final String instanceId;
    private final Clock clock;
    private final CompatibilityResolver resolver = new CompatibilityResolver();
    private final CompatibilityTrace trace;

    public CompatibilityHost(Path storeRoot, String profileId, String instanceId) throws IOException {
        this(new CompatibilityStore(storeRoot), profileId, instanceId, Clock.systemUTC(),
                new CompatibilityTrace());
    }

    CompatibilityHost(CompatibilityStore store, String profileId, String instanceId,
                      Clock clock, CompatibilityTrace trace) {
        this.store = store;
        this.profileId = CompatibilityGrant.scopeId(profileId);
        this.instanceId = CompatibilityGrant.scopeId(instanceId);
        this.clock = clock;
        this.trace = trace;
    }

    public List<String> operations() {
        return CompatibilityGrant.KNOWN_OPERATIONS.stream().sorted().toList();
    }

    public List<CompatibilityStore.StoredPack> list(CompatibilityGrant grant) throws IOException {
        authorize(grant, "compat.list", CompatibilityPack.Risk.LOW);
        return store.list();
    }

    public PackInspection inspect(CompatibilityGrant grant, String coordinate) throws IOException {
        authorize(grant, "compat.inspect", CompatibilityPack.Risk.LOW);
        CompatibilityStore.StoredPack stored = store.require(coordinate);
        CompatibilityPack pack = store.load(coordinate);
        return new PackInspection(stored, pack.manifest(), pack.documents().keySet().stream().sorted().toList(),
                pack.manifest().runtime().nativeCode()
                        ? "NATIVE_PROTOCOL_DECLARED_DYNAMIC_EXECUTION_NOT_OPEN"
                        : "DECLARATIVE_ONLY");
    }

    public Diagnosis diagnose(CompatibilityGrant grant, EnvironmentFingerprint environment) throws IOException {
        authorize(grant, "compat.diagnose", CompatibilityPack.Risk.LOW);
        CompatibilityResolver.Resolution resolution = resolve(environment);
        return new Diagnosis(environment, resolution.matchedPacks(),
                List.copyOf(resolution.capabilities().values()), resolution.conflicts(),
                resolution.suppressions(), resolution.capabilities().size(),
                resolution.capabilities().values().stream().filter(CompatibilityPack.Capability::enabled).count());
    }

    public CompatibilityStore.StoredPack install(
            CompatibilityGrant grant, Path archive, String source, String operationId) throws IOException {
        authorize(grant, "compat.install", CompatibilityPack.Risk.MEDIUM);
        return store.install(archive, source, operation(operationId));
    }

    public CompatibilityStore.StoredPack update(
            CompatibilityGrant grant, Path archive, String source, String operationId) throws IOException {
        authorize(grant, "compat.update", CompatibilityPack.Risk.MEDIUM);
        return store.install(archive, source, operation(operationId));
    }

    public CompatibilityStore.StoredPack patch(
            CompatibilityGrant grant, Path archive, String source, String operationId) throws IOException {
        authorize(grant, "compat.patch", CompatibilityPack.Risk.MEDIUM);
        CompatibilityPack pack = new CompatibilityPackLoader().load(archive);
        if (pack.manifest().type() != CompatibilityPack.PackType.PATCH) {
            throw new IOException("PATCH_OPERATION_REQUIRES_PATCH_PACK");
        }
        return store.install(archive, source, operation(operationId));
    }

    public CompatibilityStore.StoredPack recordEvidence(
            CompatibilityGrant grant, String coordinate, String evidenceId, String kind,
            CompatibilityPack.MatchLevel level, boolean passed, String summary,
            String artifactHash, String operationId) throws IOException {
        authorize(grant, "compat.record_evidence", CompatibilityPack.Risk.MEDIUM);
        return store.recordEvidence(coordinate, new CompatibilityStore.Evidence(
                evidenceId, kind, level, passed, clock.instant().toString(), summary,
                artifactHash == null ? "" : artifactHash), operation(operationId));
    }

    public CompatibilityStore.StoredPack index(
            CompatibilityGrant grant, String coordinate, String operationId) throws IOException {
        authorize(grant, "compat.index", CompatibilityPack.Risk.MEDIUM);
        return store.indexVerified(coordinate, operation(operationId));
    }

    public CompatibilityStore.StoredPack activate(
            CompatibilityGrant grant, String coordinate, EnvironmentFingerprint environment,
            String operationId) throws IOException {
        authorize(grant, "compat.activate", CompatibilityPack.Risk.HIGH);
        if (!instanceId.equals(environment.instanceId())) throw new SecurityException("INSTANCE_SCOPE_MISMATCH");
        CompatibilityPack pack = store.load(coordinate);
        CompatibilityResolver.MatchedPack match = resolver.match(environment,
                new CompatibilityResolver.Candidate(pack, store.require(coordinate).state(), "", false));
        if (match.level() == CompatibilityPack.MatchLevel.INCOMPATIBLE
                || match.level() == CompatibilityPack.MatchLevel.UNKNOWN) {
            throw new IOException("PACK_ENVIRONMENT_INCOMPATIBLE");
        }
        return store.activate(coordinate, environment, (record, loaded, actual) -> {
            CompatibilityResolver.MatchedPack checked = resolver.match(actual,
                    new CompatibilityResolver.Candidate(loaded, record.state(), "", false));
            return checked.level() != CompatibilityPack.MatchLevel.INCOMPATIBLE
                    && checked.level() != CompatibilityPack.MatchLevel.UNKNOWN;
        }, operation(operationId));
    }

    public CompatibilityStore.StoredPack deactivate(
            CompatibilityGrant grant, String coordinate, String operationId) throws IOException {
        authorize(grant, "compat.deactivate", CompatibilityPack.Risk.MEDIUM);
        return store.changeState(coordinate, CompatibilityPack.PackState.DISABLED, operation(operationId));
    }

    public CompatibilityStore.StoredPack quarantine(
            CompatibilityGrant grant, String coordinate, String operationId) throws IOException {
        authorize(grant, "compat.quarantine", CompatibilityPack.Risk.HIGH);
        return store.changeState(coordinate, CompatibilityPack.PackState.QUARANTINED, operation(operationId));
    }

    public CompatibilityStore.StoredPack rollback(
            CompatibilityGrant grant, String packId, String operationId) throws IOException {
        authorize(grant, "compat.rollback", CompatibilityPack.Risk.HIGH);
        return store.rollback(packId, operation(operationId));
    }

    public void remove(CompatibilityGrant grant, String coordinate, String operationId) throws IOException {
        authorize(grant, "compat.remove", CompatibilityPack.Risk.HIGH);
        store.remove(coordinate, operation(operationId));
    }

    public Path export(CompatibilityGrant grant, String coordinate, Path destination) throws IOException {
        authorize(grant, "compat.export", CompatibilityPack.Risk.LOW);
        return store.export(coordinate, destination);
    }

    public CompatibilityResolver.Resolution resolve(EnvironmentFingerprint environment) throws IOException {
        if (!instanceId.equals(environment.instanceId())) throw new SecurityException("INSTANCE_SCOPE_MISMATCH");
        List<CompatibilityResolver.Candidate> active = new ArrayList<>();
        for (CompatibilityStore.StoredPack stored : store.list()) {
            if (stored.state() == CompatibilityPack.PackState.ACTIVE) {
                try {
                    active.add(new CompatibilityResolver.Candidate(store.load(stored.coordinate()),
                            stored.state(), stored.activationFingerprint(), false));
                } catch (IOException corrupt) {
                    // One invalid pack is isolated from the rest; it publishes no capabilities.
                    trace.record(instanceId, "pack.load", stored.coordinate(), "QUARANTINED_VIEW",
                            Map.of("code", corrupt.getMessage() == null ? "PACK_LOAD_FAILED" : corrupt.getMessage()));
                }
            }
        }
        return resolver.resolve(environment, active);
    }

    /** Returns an already declared contract; execution remains in the bounded Tool Gateway. */
    public DeclarativeCall resolveCall(EnvironmentFingerprint environment, String capabilityId) throws IOException {
        CompatibilityPack.Capability capability = resolve(environment).requireCallable(capabilityId);
        CompatibilityTrace.Entry entry = trace.record(instanceId, capabilityId,
                capability.sourcePack(), "RESOLVED",
                Map.of("risk", capability.risk().name(), "kind", capability.kind()));
        return new DeclarativeCall(capability, entry.traceId(),
                "DECLARATIVE_CONTRACT_ONLY_TOOL_GATEWAY_EXECUTION_REQUIRED");
    }

    public List<CompatibilityTrace.Entry> recentTrace(int limit) {
        return trace.recent(limit);
    }

    public NativeExtensionPolicy nativeExtensionPolicy() {
        return new NativeExtensionPolicy("mcac-compat-native/1",
                List.of("DECLARED_ARTIFACT_HASH", "EXPLICIT_PERMISSIONS", "RESTART_ISOLATION"),
                false, "DYNAMIC_EXECUTION_NOT_OPEN");
    }

    private void authorize(CompatibilityGrant grant, String operation, CompatibilityPack.Risk risk) {
        if (grant == null) throw new SecurityException("COMPAT_GRANT_REQUIRED");
        grant.require(operation, store.root(), profileId, instanceId, risk, clock);
    }

    private static String operation(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("INVALID_OPERATION_ID");
        }
        return value;
    }

    public record PackInspection(CompatibilityStore.StoredPack stored,
                                 CompatibilityPack.Manifest manifest,
                                 List<String> documents,
                                 String executionMode) {}

    public record Diagnosis(EnvironmentFingerprint environment,
                            List<CompatibilityResolver.MatchedPack> matchedPacks,
                            List<CompatibilityPack.Capability> capabilities,
                            List<String> conflicts,
                            List<String> suppressions,
                            int capabilityCount,
                            long enabledCapabilityCount) {}

    public record DeclarativeCall(CompatibilityPack.Capability capability, String traceId, String boundary) {}

    public record NativeExtensionPolicy(String protocol, List<String> requirements,
                                        boolean dynamicExecutionAvailable, String status) {}
}
