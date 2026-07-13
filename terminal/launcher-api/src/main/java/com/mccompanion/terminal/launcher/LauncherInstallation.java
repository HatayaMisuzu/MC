package com.mccompanion.terminal.launcher;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record LauncherInstallation(
        String launcherId, LauncherType type, String detectedVersion, Path executable,
        Path dataDirectory, List<Path> minecraftRoots, DetectionConfidence confidence,
        Map<String, String> evidence) {
    public LauncherInstallation {
        minecraftRoots = List.copyOf(minecraftRoots);
        evidence = Map.copyOf(evidence);
    }
}
