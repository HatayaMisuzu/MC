package com.mccompanion.terminal;

import static org.junit.jupiter.api.Assertions.*;

import com.mccompanion.terminal.launcher.*;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalDiagnosticServiceTest {
    @TempDir Path temp;

    @Test void includesBoundedBrainAndSearchProtocolDoctors() throws Exception {
        Path game=temp.resolve("game");Files.createDirectories(game.resolve("mods"));
        MinecraftInstance instance=new MinecraftInstance("one","launcher","Test",temp,temp,game,
                game.resolve("mods"),game.resolve("config"),game.resolve("logs"),"1.21.1",LoaderType.FABRIC,
                "0.16",21,Optional.empty(),InstanceIsolation.VERSION_DIRECTORY,DetectionConfidence.HIGH);
        Path profileDir=temp.resolve("control/profiles/one");Files.createDirectories(profileDir);
        RuntimeProfile profile=new RuntimeProfile("one",profileDir,temp.resolve("runtime.cmd"),8766,18766);
        new BrainConfigurationService().disable(profile);
        new SearchConfigurationService().disable(profile);
        LauncherInstallation launcher=new LauncherInstallation("launcher",LauncherType.PCL2,"test",
                temp.resolve("PCL2.exe"),temp,List.of(temp),DetectionConfidence.HIGH,Map.of());

        var results=new TerminalDiagnosticService().run(instance,profile,launcher,temp.resolve("control"));
        var brain=results.stream().filter(value->value.code().equals("brain.protocol")).findFirst().orElseThrow();
        var search=results.stream().filter(value->value.code().equals("search.protocol")).findFirst().orElseThrow();
        assertEquals(com.mccompanion.terminal.diagnostics.DiagnosticResult.Severity.WARNING,brain.severity());
        assertEquals("DISABLED", brain.evidence().get("status"));
        assertEquals(com.mccompanion.terminal.diagnostics.DiagnosticResult.Severity.PASS,search.severity());
        assertEquals("false",search.evidence().get("networkAttempted"));
    }

    @Test void identifiesForge1201AsAFullBridgeDoctorTarget() throws Exception {
        Path game = temp.resolve("forge-game");
        Files.createDirectories(game.resolve("mods"));
        MinecraftInstance instance = new MinecraftInstance(
                "forge-one", "launcher", "Forge Test", temp, temp, game,
                game.resolve("mods"), game.resolve("config"), game.resolve("logs"),
                "1.20.1", LoaderType.FORGE, "47.4.10", 17, Optional.empty(),
                InstanceIsolation.VERSION_DIRECTORY, DetectionConfidence.HIGH);
        Path profileDir = temp.resolve("control/profiles/forge-one");
        Files.createDirectories(profileDir);
        RuntimeProfile profile = new RuntimeProfile(
                "forge-one", profileDir, temp.resolve("runtime.cmd"), 8767, 18767);
        new ProviderConfigurationService().disable(profile);
        new BrainConfigurationService().disable(profile);
        new SearchConfigurationService().disable(profile);
        LauncherInstallation launcher = new LauncherInstallation(
                "launcher", LauncherType.PCL2, "test", temp.resolve("PCL2.exe"),
                temp, List.of(temp), DetectionConfidence.HIGH, Map.of());

        var results = new TerminalDiagnosticService().run(
                instance, profile, launcher, temp.resolve("control"));
        var bridge = results.stream()
                .filter(value -> value.code().equals("loader.full_bridge"))
                .findFirst().orElseThrow();
        assertEquals(com.mccompanion.terminal.diagnostics.DiagnosticResult.Severity.PASS,
                bridge.severity());
        assertEquals("FORGE", bridge.evidence().get("loader"));
        assertEquals("1.20.1", bridge.evidence().get("minecraftVersion"));
        var codes = results.stream().map(value -> value.code()).collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.containsAll(java.util.Set.of(
                "runtime.profile", "runtime.health", "mcp.protocol",
                "registry.generic_tools", "navigation.global_tool", "brain.protocol")));
    }
}
