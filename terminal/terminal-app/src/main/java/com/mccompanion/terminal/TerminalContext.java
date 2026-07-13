package com.mccompanion.terminal;

import com.mccompanion.terminal.hmcl.HmclLauncherAdapter;
import com.mccompanion.terminal.launcher.*;
import com.mccompanion.terminal.pcl2.Pcl2LauncherAdapter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class TerminalContext {
    private final List<LauncherAdapter> adapters = List.of(new Pcl2LauncherAdapter(), new HmclLauncherAdapter());
    List<LauncherInstallation> launchers(List<Path> roots) {
        DiscoveryContext context = new DiscoveryContext(roots, true);
        return adapters.stream().flatMap(adapter -> adapter.discover(context).stream()).toList();
    }
    List<MinecraftInstance> instances(List<Path> roots) {
        List<MinecraftInstance> result = new ArrayList<>();
        for (LauncherInstallation launcher : launchers(roots)) {
            adapters.stream().filter(adapter -> adapter.type() == launcher.type()).findFirst()
                    .ifPresent(adapter -> result.addAll(adapter.discoverInstances(launcher)));
        }
        return result;
    }
    MinecraftInstance requireInstance(List<Path> roots, String idOrName) {
        return instances(roots).stream().filter(value -> value.instanceId().equalsIgnoreCase(idOrName)
                        || value.displayName().equalsIgnoreCase(idOrName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + idOrName));
    }
}
