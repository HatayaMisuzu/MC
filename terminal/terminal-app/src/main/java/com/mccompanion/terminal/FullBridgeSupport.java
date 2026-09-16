package com.mccompanion.terminal;

import com.mccompanion.terminal.launcher.LoaderType;
import com.mccompanion.terminal.launcher.MinecraftInstance;

/** Versioned product support boundary for authenticated Runtime-connected Loader bridges. */
final class FullBridgeSupport {
    private FullBridgeSupport() {
    }

    static boolean supports(MinecraftInstance instance) {
        return com.mccompanion.terminal.install.InstallPlanner.target(instance)
                .map(com.mccompanion.protocol.target.TargetDescriptor::fullBridge).orElse(false);
    }
}
