package com.mccompanion.terminal;

import com.mccompanion.terminal.launcher.LoaderType;
import com.mccompanion.terminal.launcher.MinecraftInstance;

/** Versioned product support boundary for authenticated Runtime-connected Loader bridges. */
final class FullBridgeSupport {
    private FullBridgeSupport() {
    }

    static boolean supports(MinecraftInstance instance) {
        return (instance.loader() == LoaderType.FABRIC
                        && instance.minecraftVersion().equals("1.21.1"))
                || (instance.loader() == LoaderType.FORGE
                        && instance.minecraftVersion().equals("1.20.1"));
    }
}
