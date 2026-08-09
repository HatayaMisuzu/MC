package com.mccompanion.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompatibilityLifecycleTest {
    @TempDir Path temporary;

    @Test
    void malformedCoordinatesReturnStableDomainErrors() throws Exception {
        Path store = temporary.resolve("malformed-store");
        CompatibilityHost host = new CompatibilityHost(store, "profile-a", "instance-a");
        CompatibilityGrant grant = CompatibilityPackFixture.grant(store);
        for (String coordinate : List.of("", "missing-version@", "@missing-id", "too@many@parts")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> host.inspect(grant, coordinate));
            assertEquals("INVALID_PACK_COORDINATE", failure.getMessage());
        }
    }

    @Test
    void rollbackRechecksDependenciesAndConflictsBeforeChangingActiveVersion() throws Exception {
        Path store = temporary.resolve("rollback-validation-store");
        CompatibilityHost host = new CompatibilityHost(store, "profile-a", "instance-a");
        CompatibilityGrant grant = CompatibilityPackFixture.grant(store);
        EnvironmentFingerprint environment = CompatibilityPackFixture.environment("");

        Path dependency = CompatibilityPackFixture.pack(temporary, "fixture.dependency", "1.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of(), List.of(), "fixture.dependency.read", "LOW", "dependency");
        var dependencyRecord = activate(host, grant, environment, dependency, "dependency");
        Path versionOne = CompatibilityPackFixture.pack(temporary, "fixture.rollback", "1.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of("fixture.dependency"), List.of("fixture.conflict"),
                "fixture.rollback.read", "LOW", "v1");
        activate(host, grant, environment, versionOne, "rollback-v1");
        Path versionTwo = CompatibilityPackFixture.pack(temporary, "fixture.rollback", "2.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of(), List.of(), "fixture.rollback.read", "LOW", "v2");
        activate(host, grant, environment, versionTwo, "rollback-v2");

        host.deactivate(grant, dependencyRecord.coordinate(), "disable-dependency");
        IOException missing = assertThrows(IOException.class,
                () -> host.rollback(grant, "fixture.rollback", environment, "rollback-missing-dependency"));
        assertTrue(missing.getMessage().startsWith("PACK_DEPENDENCY_MISSING:"));
        assertEquals("v2", host.resolveCall(environment, "fixture.rollback.read")
                .capability().contract().path("value").asText());

        host.activate(grant, dependencyRecord.coordinate(), environment, "reactivate-dependency");
        Path conflict = CompatibilityPackFixture.pack(temporary, "fixture.conflict", "1.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of(), List.of(), "fixture.conflict.read", "LOW", "conflict");
        activate(host, grant, environment, conflict, "conflict");
        IOException conflicting = assertThrows(IOException.class,
                () -> host.rollback(grant, "fixture.rollback", environment, "rollback-conflict"));
        assertTrue(conflicting.getMessage().startsWith("PACK_CONFLICT:"));
        assertEquals("v2", host.resolveCall(environment, "fixture.rollback.read")
                .capability().contract().path("value").asText());
    }

    @Test
    void unknownModCompletesInstallUpdateRollbackAndDisableLifecycle() throws Exception {
        Path store = temporary.resolve("store");
        CompatibilityHost host = new CompatibilityHost(store, "profile-a", "instance-a");
        CompatibilityGrant grant = CompatibilityPackFixture.grant(store);
        EnvironmentFingerprint environment = CompatibilityPackFixture.environment("");
        Path first = CompatibilityPackFixture.pack(temporary, "fixture.unknownmod", "1.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of(), List.of(), "fixture.machine.inspect", "HIGH", "v1");

        var staged = host.install(grant, first, "fixture", "install-v1");
        assertEquals(CompatibilityPack.PackState.STAGING, staged.state());
        host.recordEvidence(grant, staged.coordinate(), "gametest-v1", "GAMETEST",
                CompatibilityPack.MatchLevel.EXACT_VERIFIED, true,
                "Fixture-only real Loader path evidence.", "", "evidence-v1");
        host.index(grant, staged.coordinate(), "index-v1");
        host.activate(grant, staged.coordinate(), environment, "activate-v1");
        var call = host.resolveCall(environment, "fixture.machine.inspect");
        assertEquals("v1", call.capability().contract().path("value").asText());

        Path second = CompatibilityPackFixture.pack(temporary, "fixture.unknownmod", "2.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of(), List.of(), "fixture.machine.inspect", "HIGH", "v2");
        var updated = host.update(grant, second, "fixture", "install-v2");
        host.recordEvidence(grant, updated.coordinate(), "gametest-v2", "GAMETEST",
                CompatibilityPack.MatchLevel.EXACT_VERIFIED, true,
                "Fixture-only update evidence.", "", "evidence-v2");
        host.index(grant, updated.coordinate(), "index-v2");
        host.activate(grant, updated.coordinate(), environment, "activate-v2");
        assertEquals("v2", host.resolveCall(environment, "fixture.machine.inspect")
                .capability().contract().path("value").asText());

        EnvironmentFingerprint incompatible = new EnvironmentFingerprint(
                "instance-a", "1.20.1", "forge", "47.4.0", 17, java.util.Map.of(),
                "", "", "", "", "");
        assertThrows(java.io.IOException.class,
                () -> host.rollback(grant, "fixture.unknownmod", incompatible, "rollback-invalid"));
        assertEquals("v2", host.resolveCall(environment, "fixture.machine.inspect")
                .capability().contract().path("value").asText());

        var rolledBack = host.rollback(grant, "fixture.unknownmod", environment, "rollback-v1");
        assertEquals("1.0.0", rolledBack.version());
        assertEquals("v1", host.resolveCall(environment, "fixture.machine.inspect")
                .capability().contract().path("value").asText());
        host.deactivate(grant, rolledBack.coordinate(), "disable-v1");
        assertThrows(IllegalArgumentException.class,
                () -> host.resolveCall(environment, "fixture.machine.inspect"));
        assertFalse(host.recentTrace(10).isEmpty());
    }

    @Test
    void modpackOverlayOverridesThenBecomesStaleAndSuppressesHighRiskCapability() throws Exception {
        Path store = temporary.resolve("overlay-store");
        CompatibilityHost host = new CompatibilityHost(store, "profile-a", "instance-a");
        CompatibilityGrant grant = CompatibilityPackFixture.grant(store);
        EnvironmentFingerprint original = CompatibilityPackFixture.environment("");
        Path mod = CompatibilityPackFixture.pack(temporary, "fixture.mod", "1.0.0",
                "mod", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of(), List.of(), "fixture.machine.configure", "HIGH", "mod");
        activate(host, grant, original, mod, "mod");
        Path overlay = CompatibilityPackFixture.pack(temporary, "fixture.modpack", "1.0.0",
                "modpack-overlay", "1.21.1", "fabric", "fixturemod", "a".repeat(64),
                List.of("fixture.mod"), List.of(), "fixture.machine.configure", "HIGH", "overlay");
        activate(host, grant, original, overlay, "overlay");
        assertEquals("overlay", host.resolveCall(original, "fixture.machine.configure")
                .capability().contract().path("value").asText());

        EnvironmentFingerprint changed = CompatibilityPackFixture.environment("b".repeat(64));
        var diagnosis = host.diagnose(grant, changed);
        assertTrue(diagnosis.matchedPacks().stream().anyMatch(match ->
                match.pack().manifest().id().equals("fixture.modpack") && match.stale()));
        assertThrows(IllegalStateException.class,
                () -> host.resolveCall(changed, "fixture.machine.configure"));
        assertTrue(diagnosis.suppressions().stream().anyMatch(value -> value.contains("STALE_FINGERPRINT")));
    }

    private static CompatibilityStore.StoredPack activate(CompatibilityHost host, CompatibilityGrant grant,
                                 EnvironmentFingerprint environment, Path archive, String suffix)
            throws Exception {
        var stored = host.install(grant, archive, "fixture", "install-" + suffix);
        host.recordEvidence(grant, stored.coordinate(), "evidence-" + suffix, "GAMETEST",
                CompatibilityPack.MatchLevel.EXACT_VERIFIED, true, "Fixture evidence.", "",
                "record-" + suffix);
        host.index(grant, stored.coordinate(), "index-" + suffix);
        return host.activate(grant, stored.coordinate(), environment, "activate-" + suffix);
    }
}
