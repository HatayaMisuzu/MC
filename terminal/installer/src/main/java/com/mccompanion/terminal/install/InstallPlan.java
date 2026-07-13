package com.mccompanion.terminal.install;

import com.mccompanion.terminal.launcher.MinecraftInstance;
import java.nio.file.Path;
import java.util.List;

public record InstallPlan(MinecraftInstance instance, Path artifact, Path destination,
                          List<Path> replacedFiles, boolean fabricApiMissing, String rollbackId) {
    public InstallPlan { replacedFiles = List.copyOf(replacedFiles); }
}
