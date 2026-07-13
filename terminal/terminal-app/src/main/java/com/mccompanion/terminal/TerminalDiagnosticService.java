package com.mccompanion.terminal;

import com.mccompanion.terminal.diagnostics.DiagnosticEngine;
import com.mccompanion.terminal.diagnostics.DiagnosticResult;
import com.mccompanion.terminal.install.InstallTransaction;
import com.mccompanion.terminal.launcher.LauncherInstallation;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.runtime.PairingService;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import com.mccompanion.terminal.runtime.WindowsRuntimeSupervisor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Combines static instance checks with control-home and live Runtime evidence. */
final class TerminalDiagnosticService {
    private static final String PROTOCOL = "mc-companion/1";

    List<DiagnosticResult> run(
            MinecraftInstance instance,
            RuntimeProfile profile,
            LauncherInstallation launcher,
            Path controlHome) {
        List<DiagnosticResult> results = new ArrayList<>(new DiagnosticEngine().run(instance));
        Path identity = profile.profileDirectory().resolve("profile.json");
        results.add(result(Files.isRegularFile(identity), "runtime.profile", "Runtime profile identity file",
                Map.of("profile", profile.instanceId()), "Run mcac runtime start " + instance.instanceId()));
        results.add(result(profile.port() >= 8766 && profile.port() <= 8866,
                "runtime.port", "Runtime profile port is in the managed range",
                Map.of("port", Integer.toString(profile.port()), "healthPort", Integer.toString(profile.healthPort())),
                "Recreate the invalid Runtime profile"));

        WindowsRuntimeSupervisor.RuntimeHealth health = new WindowsRuntimeSupervisor().status(profile);
        results.add(result(health.pidAlive(), "runtime.pid", health.pidAlive() ? "Runtime PID is alive" : "Runtime is stopped",
                Map.of("pid", String.valueOf(health.pid())), "Run mcac runtime start " + instance.instanceId()));
        results.add(result(health.healthy(), "runtime.health", health.detail(),
                Map.of("runtimeVersion", value(health.runtimeVersion()), "sessions", Integer.toString(health.sessionCount())),
                "Inspect mcac runtime logs " + instance.instanceId()));

        boolean tokensMatch;
        try { tokensMatch = new PairingService().tokensMatch(instance, profile); }
        catch (Exception failure) { tokensMatch = false; }
        results.add(result(tokensMatch, "runtime.token_match", tokensMatch ? "Pairing tokens match" : "Pairing tokens are missing or disagree",
                Map.of(), "Run mcac runtime rotate-token " + instance.instanceId() + " --yes"));
        boolean protocol = PROTOCOL.equals(health.protocolVersion());
        results.add(result(protocol, "protocol.compatible", protocol ? "Runtime protocol is compatible" : "Compatible Runtime protocol not observed",
                Map.of("expected", PROTOCOL, "actual", value(health.protocolVersion())), "Update or restart Runtime"));

        String hook = new HookService().status(instance, controlHome);
        results.add(new DiagnosticResult("INSTALLED".equals(hook) ? DiagnosticResult.Severity.PASS : DiagnosticResult.Severity.WARNING,
                "hook.state", hook, Map.of("launcher", launcher.type().name()),
                List.of("Run mcac hook install " + instance.instanceId() + " --yes")));

        boolean installed;
        try { installed = new InstallTransaction().verify(instance.gameDirectory()); }
        catch (Exception failure) { installed = false; }
        results.add(result(installed, "install.hash", installed ? "Managed artifact hash verified" : "No valid managed artifact hash",
                Map.of(), "Run mcac repair " + instance.instanceId() + " --yes"));
        return List.copyOf(results);
    }

    private static DiagnosticResult result(boolean pass, String code, String summary,
                                           Map<String, String> evidence, String repair) {
        return new DiagnosticResult(pass ? DiagnosticResult.Severity.PASS : DiagnosticResult.Severity.WARNING,
                code, summary, evidence, List.of(repair));
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }
}
