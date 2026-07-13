package com.mccompanion.terminal;

import com.mccompanion.terminal.launcher.*;
import com.mccompanion.terminal.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TokenRotationServiceTest {
    @TempDir Path temp;

    @Test void runtimeStartFailureRestoresPreviousToken() throws Exception {
        Fixture fixture = fixture();
        FakeRuntime runtime = new FakeRuntime();
        runtime.failNextStart = true;
        TokenRotationService service = new TokenRotationService(fixture.pairing, runtime, new FakeConnections(false, true));
        assertThrows(IOException.class, () -> service.rotate(fixture.instance, fixture.profile, Duration.ofMillis(10)));
        assertEquals(fixture.oldToken, Files.readString(fixture.profile.profileDirectory().resolve("pairing.token")));
        assertTrue(fixture.pairing.tokensMatch(fixture.instance, fixture.profile));
        assertTrue(runtime.running);
    }

    @Test void handshakeFailureRestoresPreviousTokenAndRuntime() throws Exception {
        Fixture fixture = fixture();
        FakeRuntime runtime = new FakeRuntime();
        TokenRotationService service = new TokenRotationService(fixture.pairing, runtime, new FakeConnections(true, false));
        assertThrows(IOException.class, () -> service.rotate(fixture.instance, fixture.profile, Duration.ofMillis(10)));
        assertEquals(fixture.oldToken, Files.readString(fixture.profile.profileDirectory().resolve("pairing.token")));
        assertTrue(fixture.pairing.tokensMatch(fixture.instance, fixture.profile));
        assertTrue(runtime.running);
    }

    private Fixture fixture() throws Exception {
        Path game = temp.resolve("game");
        Files.createDirectories(game);
        MinecraftInstance instance = new MinecraftInstance("instance", "launcher", "Test", temp, temp, game,
                game.resolve("mods"), game.resolve("config"), game.resolve("logs"), "1.21.1",
                LoaderType.FABRIC, "1", 21, Optional.empty(), InstanceIsolation.EXPLICIT, DetectionConfidence.HIGH);
        RuntimeProfile profile = new RuntimeProfileService(temp.resolve("home"), temp.resolve("runtime.exe")).ensure("instance");
        PairingService pairing = new PairingService();
        pairing.ensureConfigured(instance, profile);
        return new Fixture(instance, profile, pairing,
                Files.readString(profile.profileDirectory().resolve("pairing.token")));
    }

    private static WindowsRuntimeSupervisor.RuntimeHealth healthy() {
        return new WindowsRuntimeSupervisor.RuntimeHealth(1L, true, true, true, true,
                "test", "mc-companion/1", 1, true, "ok");
    }

    private static final class FakeRuntime implements TokenRotationService.RuntimeControl {
        boolean running = true;
        boolean failNextStart;
        public WindowsRuntimeSupervisor.RuntimeHealth status(RuntimeProfile profile) {
            return running ? healthy() : new WindowsRuntimeSupervisor.RuntimeHealth(null, false, false, false,
                    false, null, null, 0, false, "stopped");
        }
        public void stop(RuntimeProfile profile) { running = false; }
        public void start(RuntimeProfile profile) throws IOException {
            if (failNextStart) { failNextStart = false; throw new IOException("injected start failure"); }
            running = true;
        }
        public WindowsRuntimeSupervisor.RuntimeHealth awaitHealthy(RuntimeProfile profile, Duration timeout) {
            return status(profile);
        }
    }

    private record FakeConnections(boolean connected, boolean awaitResult)
            implements TokenRotationService.ConnectionProbe {
        public boolean connected(RuntimeProfile profile) { return connected; }
        public boolean awaitConnected(RuntimeProfile profile, Duration timeout) { return awaitResult; }
    }

    private record Fixture(MinecraftInstance instance, RuntimeProfile profile, PairingService pairing, String oldToken) { }
}
