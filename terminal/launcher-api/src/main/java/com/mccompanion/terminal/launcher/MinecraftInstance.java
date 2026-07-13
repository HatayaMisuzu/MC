package com.mccompanion.terminal.launcher;

import java.nio.file.Path;
import java.util.Optional;

public record MinecraftInstance(
        String instanceId, String launcherId, String displayName, Path minecraftRoot,
        Path versionDirectory, Path gameDirectory, Path modsDirectory, Path configDirectory,
        Path logsDirectory, String minecraftVersion, LoaderType loader, String loaderVersion,
        int requiredJavaMajor, Optional<Path> configuredJava, InstanceIsolation isolation,
        DetectionConfidence confidence) {
    public MinecraftInstance {
        configuredJava = configuredJava == null ? Optional.empty() : configuredJava;
    }
}
