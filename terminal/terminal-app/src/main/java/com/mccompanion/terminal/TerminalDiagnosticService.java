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
import com.fasterxml.jackson.databind.ObjectMapper;

/** Combines static instance checks with control-home and live Runtime evidence. */
final class TerminalDiagnosticService {
    private static final String PROTOCOL = "mc-companion/1";
    private static final ObjectMapper JSON = new ObjectMapper();

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
        McpProtocolDoctor.Result mcp = new McpProtocolDoctor().probe(profile);
        results.add(result(mcp.healthy(), "mcp.protocol", mcp.detail(),
                Map.of("protocolVersion", mcp.protocolVersion(), "toolCount", Integer.toString(mcp.toolCount())),
                "Start or update Runtime, then inspect MCP configuration"));
        results.add(result(mcp.genericRegistry(), "registry.generic_tools",
                mcp.genericRegistry() ? "Generic Registry discovery is available in the current Tool scope"
                        : "Generic Registry discovery is unavailable in the current Tool scope",
                Map.of("registrySearch", Boolean.toString(mcp.genericRegistry())),
                "Connect a supported Fabric body and rerun Doctor"));
        results.add(result(mcp.episodeCapsules(), "memory.episode_capsules",
                mcp.episodeCapsules() ? "Episode Capsule read surface is available"
                        : "Episode Capsule read surface is unavailable",
                Map.of("readTool", Boolean.toString(mcp.episodeCapsules())),
                "Start or update Runtime and verify migration 23"));
        results.add(result(mcp.memoryCandidateSubmission(), "memory.candidate_review",
                mcp.memoryCandidateSubmission() ? "Candidate submission is available; review remains local-only"
                        : "Memory candidate submission is unavailable",
                Map.of("submissionTool", Boolean.toString(mcp.memoryCandidateSubmission()),
                        "brainReviewTool", "false"), "Start or update Runtime and inspect the Brain page"));

        try {
            var status = new ProviderConfigurationService().status(profile);
            boolean configured = !"rules".equals(status.path("mode").asText("rules"));
            String credentialEnv = status.path("apiKeyEnv").asText("");
            boolean credentialPresent = configured && !credentialEnv.isBlank()
                    && System.getenv(credentialEnv) != null && !System.getenv(credentialEnv).isBlank();
            results.add(result(credentialPresent, "brain.live_credentials",
                    credentialPresent ? "Live Brain credential environment is present"
                            : "Live Brain run is blocked until a configured credential environment is present",
                    Map.of("providerConfigured", Boolean.toString(configured),
                            "credentialPresent", Boolean.toString(credentialPresent), "credentialStored", "false"),
                    "Configure a real external Brain and set its named environment variable"));
            int requests = budget("MCAC_LIVE_BRAIN_MAX_REQUESTS", 24);
            int input = budget("MCAC_LIVE_BRAIN_MAX_INPUT_TOKENS", 30_000);
            int output = budget("MCAC_LIVE_BRAIN_MAX_OUTPUT_TOKENS", 8_000);
            int minutes = budget("MCAC_LIVE_BRAIN_MAX_WALL_CLOCK_MINUTES", 15);
            int retries = budget("MCAC_LIVE_BRAIN_MAX_RETRIES", 2);
            boolean bounded = requests > 0 && input > 0 && output > 0 && minutes > 0 && retries >= 0;
            results.add(result(bounded, "brain.validation_budget", "Live Brain validation budgets are bounded",
                    Map.of("maxRequests", Integer.toString(requests), "maxInputTokens", Integer.toString(input),
                            "maxOutputTokens", Integer.toString(output), "maxWallClockMinutes", Integer.toString(minutes),
                            "maxRetries", Integer.toString(retries)), "Correct invalid MCAC_LIVE_BRAIN_MAX_* variables"));
            Path evidence = profile.profileDirectory().resolve("validation").resolve("live-brain-report.json");
            boolean liveEvidence = validLiveEvidence(evidence);
            results.add(result(liveEvidence, "brain.live_evidence",
                    liveEvidence ? "Sanitized three-scenario Live Brain evidence is present"
                            : "Sanitized three-scenario Live Brain evidence is not present",
                    Map.of("present", Boolean.toString(Files.isRegularFile(evidence)),
                            "threeScenariosPassed", Boolean.toString(liveEvidence)),
                    "Run the Live Brain guide and save the sanitized report under the profile validation directory"));
            var brain = new ProviderConfigurationService().test(profile);
            results.add(result(brain.success(), "brain.provider", brain.message(),
                    Map.of("model", brain.model(), "latencyMillis", Long.toString(brain.latencyMillis())),
                    "Review Brain provider settings and its credential environment variable"));
        } catch (Exception failure) {
            results.add(result(false, "brain.provider", "Brain provider could not be tested",
                    Map.of("error", failure.getClass().getSimpleName()), "Review Brain provider settings"));
        }

        try {
            var search = new SearchConfigurationService().test(profile);
            results.add(result(search.success(), "search.protocol", search.message(),
                    Map.of("code", search.code(), "networkAttempted", Boolean.toString(search.networkAttempted()),
                            "latencyMillis", Long.toString(search.latencyMillis())),
                    "Configure the Search token environment variable or disable Search"));
        } catch (Exception failure) {
            results.add(result(false, "search.configuration", "Search configuration could not be validated",
                    Map.of("error", failure.getClass().getSimpleName()), "Review Search & Privacy settings"));
        }

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

    private static int budget(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value); } catch (NumberFormatException invalid) { return -1; }
    }

    private static boolean validLiveEvidence(Path path) {
        if (!Files.isRegularFile(path)) return false;
        try {
            var value = JSON.readTree(path.toFile());
            if (!value.path("liveModel").asBoolean(false) || !value.path("scenarios").isArray()
                    || value.path("scenarios").size() != 3) return false;
            for (var scenario : value.path("scenarios")) if (!"PASS".equals(scenario.path("status").asText())) return false;
            return true;
        } catch (Exception invalid) { return false; }
    }
}
