package com.mccompanion.terminal;

import com.mccompanion.terminal.launcher.DetectionConfidence;
import com.mccompanion.terminal.launcher.InstanceIsolation;
import com.mccompanion.terminal.launcher.LoaderType;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullBridgeSupportTest {
    @Test
    void onlyCurrentFabricAndForgeTargetsExposeTheFullBridge() {
        assertTrue(FullBridgeSupport.supports(instance(LoaderType.FABRIC, "1.21.1", 21)));
        assertTrue(FullBridgeSupport.supports(instance(LoaderType.FORGE, "1.20.1", 17)));
        assertFalse(FullBridgeSupport.supports(instance(LoaderType.NEOFORGE, "1.21.1", 21)));
        assertFalse(FullBridgeSupport.supports(instance(LoaderType.FORGE, "1.21.1", 21)));
        assertFalse(FullBridgeSupport.supports(instance(LoaderType.FABRIC, "1.20.1", 17)));
    }

    private static MinecraftInstance instance(LoaderType loader, String minecraft, int java) {
        Path root = Path.of("test-instance");
        return new MinecraftInstance(
                loader.name().toLowerCase() + '-' + minecraft,
                "test-launcher",
                "Test",
                root,
                root,
                root,
                root.resolve("mods"),
                root.resolve("logs"),
                root.resolve("options.txt"),
                minecraft,
                loader,
                "test",
                java,
                Optional.empty(),
                InstanceIsolation.EXPLICIT,
                DetectionConfidence.HIGH);
    }
}
