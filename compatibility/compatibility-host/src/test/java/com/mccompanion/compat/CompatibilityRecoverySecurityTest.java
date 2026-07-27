package com.mccompanion.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CompatibilityRecoverySecurityTest {
    @TempDir Path temporary;

    @Test
    void everyCrashWindowRecoversTheCompleteOldIndex() throws Exception {
        Path archive = CompatibilityPackFixture.pack(temporary, "fixture.crash", "1.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of(), List.of(), "fixture.crash.inspect", "LOW", "safe");
        for (CompatibilityStore.Phase phase : CompatibilityStore.Phase.values()) {
            Path root = temporary.resolve("store-" + phase.name());
            CompatibilityStore crashing = new CompatibilityStore(root, Clock.systemUTC(),
                    observed -> {
                        if (observed == phase) throw new SimulatedCrash();
                    });
            assertThrows(SimulatedCrash.class,
                    () -> crashing.install(archive, "fixture", "crash-" + phase.name()));
            CompatibilityStore recovered = new CompatibilityStore(root);
            assertTrue(recovered.list().isEmpty(), "old index not restored after " + phase);
            assertFalse(Files.exists(root.resolve("journal/transaction.json")));
        }
    }

    @Test
    void healthFailureDependencyConflictAndCorruptionNeverPublishCapability() throws Exception {
        Path root = temporary.resolve("failure-store");
        CompatibilityStore store = new CompatibilityStore(root);
        Path archive = CompatibilityPackFixture.pack(temporary, "fixture.health", "1.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of(), List.of(), "fixture.health.run", "HIGH", "health");
        var staged = store.install(archive, "fixture", "install");
        store.recordEvidence(staged.coordinate(), new CompatibilityStore.Evidence(
                "evidence-health", "GAMETEST", CompatibilityPack.MatchLevel.EXACT_VERIFIED,
                true, "2026-07-27T00:00:00Z", "Health evidence.", ""), "evidence");
        store.indexVerified(staged.coordinate(), "index");
        assertThrows(IOException.class, () -> store.activate(staged.coordinate(),
                CompatibilityPackFixture.environment(""), (record, pack, environment) -> false,
                "activate"));
        assertEquals(CompatibilityPack.PackState.VERIFIED, store.require(staged.coordinate()).state());

        Path missing = CompatibilityPackFixture.pack(temporary, "fixture.dependent", "1.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of("fixture.absent"), List.of(), "fixture.dep.run", "LOW", "dep");
        var dependent = verify(store, missing, "dependent");
        assertThrows(IOException.class, () -> store.activate(dependent.coordinate(),
                CompatibilityPackFixture.environment(""), (record, pack, environment) -> true,
                "activate-dependent"));

        Files.writeString(root.resolve("cache/objects/" + staged.contentHash() + ".mcac-compat"),
                "corrupt", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> store.load(staged.coordinate()));
    }

    @Test
    void archiveTraversalHashIdentityTamperingAndScopeEscapesAreRejected() throws Exception {
        Path unsafe = CompatibilityPackFixture.archive(temporary.resolve("unsafe.mcac-compat"),
                Map.of("../manifest.yaml", "x".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> new CompatibilityPackLoader().load(unsafe));

        Path root = temporary.resolve("security-store");
        CompatibilityHost host = new CompatibilityHost(root, "profile-a", "instance-a");
        CompatibilityGrant grant = CompatibilityPackFixture.grant(root);
        Path first = CompatibilityPackFixture.pack(temporary, "fixture.identity", "1.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of(), List.of(), "fixture.identity.read", "LOW", "one");
        Path second = CompatibilityPackFixture.pack(temporary.resolve("other"), "fixture.identity", "1.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of(), List.of(), "fixture.identity.read", "LOW", "two");
        host.install(grant, first, "fixture", "install-one");
        assertThrows(IOException.class, () -> host.install(grant, second, "fixture", "install-two"));

        CompatibilityGrant wrongInstance = new CompatibilityGrant("grant-wrong", "codex-agent",
                "terminal-controller", "profile-a", "instance-b", root,
                CompatibilityGrant.KNOWN_OPERATIONS, java.time.Instant.now().plusSeconds(60),
                CompatibilityPack.Risk.CRITICAL);
        assertThrows(SecurityException.class, () -> host.list(wrongInstance));

        Files.writeString(root.resolve("index/active-index.json"), "{}", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> host.list(grant));
        assertFalse(host.nativeExtensionPolicy().dynamicExecutionAvailable());
        assertEquals("DYNAMIC_EXECUTION_NOT_OPEN", host.nativeExtensionPolicy().status());
    }

    @Test
    void oneDisabledPackAndOneInstanceCannotAffectAnother() throws Exception {
        Path rootA = temporary.resolve("isolated-a");
        Path rootB = temporary.resolve("isolated-b");
        CompatibilityHost first = new CompatibilityHost(rootA, "profile-a", "instance-a");
        CompatibilityHost second = new CompatibilityHost(rootB, "profile-b", "instance-b");
        Path pack = CompatibilityPackFixture.pack(temporary, "fixture.isolated", "1.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of(), List.of(), "fixture.isolated.read", "LOW", "a");
        first.install(CompatibilityPackFixture.grant(rootA), pack, "fixture", "install-a");
        assertTrue(second.list(new CompatibilityGrant("grant-b", "codex-agent",
                "terminal-controller", "profile-b", "instance-b", rootB,
                CompatibilityGrant.KNOWN_OPERATIONS, java.time.Instant.now().plusSeconds(60),
                CompatibilityPack.Risk.CRITICAL)).isEmpty());
    }

    private static CompatibilityStore.StoredPack verify(
            CompatibilityStore store, Path archive, String suffix) throws Exception {
        var staged = store.install(archive, "fixture", "install-" + suffix);
        store.recordEvidence(staged.coordinate(), new CompatibilityStore.Evidence(
                "evidence-" + suffix, "GAMETEST", CompatibilityPack.MatchLevel.EXACT_VERIFIED,
                true, "2026-07-27T00:00:00Z", "Fixture evidence.", ""), "evidence-" + suffix);
        return store.indexVerified(staged.coordinate(), "index-" + suffix);
    }

    private static final class SimulatedCrash extends Error {}
}
