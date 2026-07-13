package com.mccompanion.terminal;

import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.runtime.PairingService;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import com.mccompanion.terminal.runtime.WindowsRuntimeSupervisor;

import java.io.IOException;
import java.time.Duration;

/** Executes token rotation as a recoverable transaction, including Runtime and Mod verification. */
final class TokenRotationService {
    private final PairingService pairing;
    private final RuntimeControl runtime;
    private final ConnectionProbe connections;

    TokenRotationService() {
        this(new PairingService(), new ProductionRuntimeControl(), new ProductionConnectionProbe());
    }

    TokenRotationService(PairingService pairing, RuntimeControl runtime, ConnectionProbe connections) {
        this.pairing = pairing;
        this.runtime = runtime;
        this.connections = connections;
    }

    Report rotate(MinecraftInstance instance, RuntimeProfile profile, Duration timeout) throws IOException {
        PairingService.Snapshot snapshot = pairing.snapshot(instance, profile);
        WindowsRuntimeSupervisor.RuntimeHealth before = runtime.status(profile);
        boolean wasRunning = before.pidAlive();
        boolean requiredReconnect = before.healthy() && connections.connected(profile);
        try {
            if (wasRunning) runtime.stop(profile);
            pairing.rotate(instance, profile);
            pairing.ensureConfigured(instance, profile);
            if (wasRunning) {
                runtime.start(profile);
                WindowsRuntimeSupervisor.RuntimeHealth health = runtime.awaitHealthy(profile, timeout);
                if (!health.healthy()) throw new IOException("New Runtime failed authenticated health verification");
            }
            if (!pairing.tokensMatch(instance, profile)) throw new IOException("Rotated token copies do not match");
            if (requiredReconnect && !connections.awaitConnected(profile, timeout)) {
                throw new IOException("Fabric Mod did not re-handshake after token rotation");
            }
            return new Report(wasRunning, requiredReconnect, true, false);
        } catch (Exception failure) {
            IOException rollbackFailure = null;
            try { if (runtime.status(profile).pidAlive()) runtime.stop(profile); }
            catch (Exception error) { rollbackFailure = append(rollbackFailure, error); }
            try { pairing.restore(snapshot); }
            catch (Exception error) { rollbackFailure = append(rollbackFailure, error); }
            if (wasRunning) {
                try {
                    runtime.start(profile);
                    if (!runtime.awaitHealthy(profile, timeout).healthy()) {
                        throw new IOException("Restored Runtime did not become healthy");
                    }
                    if (requiredReconnect && !connections.awaitConnected(profile, timeout)) {
                        throw new IOException("Fabric Mod did not reconnect to restored Runtime");
                    }
                } catch (Exception error) { rollbackFailure = append(rollbackFailure, error); }
            }
            IOException result = new IOException("Token rotation failed and previous configuration was restored: "
                    + failure.getMessage(), failure);
            if (rollbackFailure != null) result.addSuppressed(rollbackFailure);
            throw result;
        }
    }

    private static IOException append(IOException aggregate, Exception failure) {
        IOException result = aggregate == null ? new IOException("Token rotation rollback was incomplete") : aggregate;
        result.addSuppressed(failure);
        return result;
    }

    interface RuntimeControl {
        WindowsRuntimeSupervisor.RuntimeHealth status(RuntimeProfile profile);
        void stop(RuntimeProfile profile) throws IOException;
        void start(RuntimeProfile profile) throws IOException;
        WindowsRuntimeSupervisor.RuntimeHealth awaitHealthy(RuntimeProfile profile, Duration timeout);
    }

    interface ConnectionProbe {
        boolean connected(RuntimeProfile profile);
        boolean awaitConnected(RuntimeProfile profile, Duration timeout);
    }

    private static final class ProductionRuntimeControl implements RuntimeControl {
        private final WindowsRuntimeSupervisor supervisor = new WindowsRuntimeSupervisor();
        public WindowsRuntimeSupervisor.RuntimeHealth status(RuntimeProfile profile) { return supervisor.status(profile); }
        public void stop(RuntimeProfile profile) throws IOException { supervisor.stop(profile); }
        public void start(RuntimeProfile profile) throws IOException { supervisor.start(profile); }
        public WindowsRuntimeSupervisor.RuntimeHealth awaitHealthy(RuntimeProfile profile, Duration timeout) {
            return supervisor.awaitHealthy(profile, timeout);
        }
    }

    private static final class ProductionConnectionProbe implements ConnectionProbe {
        private final ConnectionService service = new ConnectionService();
        public boolean connected(RuntimeProfile profile) { return service.status(profile).connected(); }
        public boolean awaitConnected(RuntimeProfile profile, Duration timeout) {
            try { return service.waitForHandshake(profile, timeout).connected(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
    }

    record Report(boolean runtimeRestarted, boolean modReconnected, boolean committed, boolean rolledBack) { }
}
