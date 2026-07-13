package com.mccompanion.terminal.launcher;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface LauncherAdapter {
    LauncherType type();
    List<LauncherInstallation> discover(DiscoveryContext context);
    List<MinecraftInstance> discoverInstances(LauncherInstallation launcher);
    default Optional<HookPlan> planHookInstall(MinecraftInstance instance, Path mcacExecutable) {
        return Optional.empty();
    }
}
