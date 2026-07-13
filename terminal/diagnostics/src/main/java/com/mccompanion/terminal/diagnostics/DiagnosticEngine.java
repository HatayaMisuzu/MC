package com.mccompanion.terminal.diagnostics;

import com.mccompanion.terminal.install.InstallPlanner;
import com.mccompanion.terminal.launcher.DetectionConfidence;
import com.mccompanion.terminal.launcher.LoaderType;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.probe.ModJarInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DiagnosticEngine {
    private final ModJarInspector mods = new ModJarInspector();
    public List<DiagnosticResult> run(MinecraftInstance instance) {
        List<DiagnosticResult> result = new ArrayList<>();
        result.add(check("instance.confidence", instance.confidence() == DetectionConfidence.HIGH,
                instance.confidence() == DetectionConfidence.UNKNOWN ? DiagnosticResult.Severity.BLOCKED : DiagnosticResult.Severity.WARNING,
                "Game directory confidence: " + instance.confidence()));
        result.add(check("target.support", InstallPlanner.isSupported(instance), DiagnosticResult.Severity.BLOCKED,
                InstallPlanner.isSupported(instance) ? "Supported Minecraft/loader target" :
                        "Unsupported target: Minecraft " + instance.minecraftVersion() + " / " + instance.loader()));
        int expected = switch (instance.minecraftVersion()) { case "1.20.1" -> 17; case "1.21.1" -> 21; default -> 0; };
        boolean javaKnown = instance.requiredJavaMajor() > 0;
        result.add(new DiagnosticResult(expected == 0 ? DiagnosticResult.Severity.UNKNOWN
                : instance.requiredJavaMajor() == expected ? DiagnosticResult.Severity.PASS : DiagnosticResult.Severity.WARNING,
                "java.requirement", javaKnown ? "Instance requires Java " + instance.requiredJavaMajor() : "Java requirement is unknown", Map.of()));
        boolean writable = writableOrCreatable(instance.modsDirectory());
        result.add(check("mods.writable", writable, DiagnosticResult.Severity.BLOCKED,
                writable ? "Mods directory is writable" : "Mods directory is not writable"));
        List<ModJarInspector.ModInfo> installed = mods.inspectDirectory(instance.modsDirectory());
        long companions = installed.stream().filter(ModJarInspector.ModInfo::companion).count();
        result.add(new DiagnosticResult(companions > 1 ? DiagnosticResult.Severity.BLOCKED : DiagnosticResult.Severity.PASS,
                "mods.companion_duplicates", companions > 1 ? "Multiple Companion JARs found" : "No duplicate Companion JAR", Map.of("count", Long.toString(companions))));
        if (instance.loader() == LoaderType.FABRIC) {
            boolean api = installed.stream().anyMatch(mod -> mod.id().equals("fabric-api"));
            result.add(check("mods.fabric_api", api, DiagnosticResult.Severity.WARNING,
                    api ? "Fabric API is installed" : "Fabric API is missing"));
        }
        return result;
    }
    private static boolean writableOrCreatable(java.nio.file.Path path) {
        if (Files.isDirectory(path)) return Files.isWritable(path);
        java.nio.file.Path parent = path.getParent();
        return parent != null && Files.isDirectory(parent) && Files.isWritable(parent);
    }
    private static DiagnosticResult check(String code, boolean pass, DiagnosticResult.Severity failure, String summary) {
        return new DiagnosticResult(pass ? DiagnosticResult.Severity.PASS : failure, code, summary, Map.of());
    }
}
