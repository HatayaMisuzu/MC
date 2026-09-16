package com.mccompanion.terminal.install;

import com.mccompanion.terminal.launcher.MinecraftInstance;
import java.nio.file.Path;
import java.util.List;

public record InstallPlan(MinecraftInstance instance, Path artifact, Path destination,
                          List<Path> replacedFiles, boolean fabricApiMissing, String rollbackId,
                          String artifactSha256) {
    public InstallPlan {
        replacedFiles = List.copyOf(replacedFiles);
        if (artifactSha256 == null || !artifactSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Artifact digest required");
    }
    public InstallPlan(MinecraftInstance instance, Path artifact, Path destination,
                       List<Path> replacedFiles, boolean fabricApiMissing, String rollbackId) throws java.io.IOException {
        this(instance, artifact, destination, replacedFiles, fabricApiMissing, rollbackId, ArtifactValidator.sha256(artifact));
    }
}
