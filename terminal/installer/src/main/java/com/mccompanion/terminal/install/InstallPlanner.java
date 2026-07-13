package com.mccompanion.terminal.install;

import com.mccompanion.terminal.launcher.LoaderType;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.launcher.DetectionConfidence;
import com.mccompanion.terminal.probe.ModJarInspector;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class InstallPlanner {
    private final ModJarInspector inspector = new ModJarInspector();
    public InstallPlan plan(MinecraftInstance instance, Path artifact) throws IOException {
        if (!isSupported(instance)) throw new IOException("Unsupported target: Minecraft " + instance.minecraftVersion() + " / " + instance.loader());
        if(instance.confidence()!=DetectionConfidence.HIGH)throw new IOException("Installation requires HIGH gameDir confidence; explicitly confirm the absolute game directory first");
        validateInstancePaths(instance);
        ModJarInspector.ModInfo artifactInfo = inspector.inspect(artifact);
        String expected = instance.loader().name().toLowerCase();
        if (!artifactInfo.companion() || !artifactInfo.metadataType().equals(expected))
            throw new IOException("Artifact metadata does not match target loader " + instance.loader());
        List<Path> old = new ArrayList<>();
        boolean fabricApi = false;
        for (ModJarInspector.ModInfo mod : inspector.inspectDirectory(instance.modsDirectory())) {
            if (mod.companion()) old.add(mod.jar());
            if (mod.id().equals("fabric-api")) fabricApi = true;
        }
        Path destination = instance.modsDirectory().resolve(artifact.getFileName()).normalize();
        if (!destination.startsWith(instance.modsDirectory().toAbsolutePath().normalize())) throw new IOException("Unsafe destination");
        return new InstallPlan(instance, artifact.toAbsolutePath().normalize(), destination, old,
                instance.loader() == LoaderType.FABRIC && !fabricApi, Long.toUnsignedString(Instant.now().toEpochMilli(), 36));
    }
    private static void validateInstancePaths(MinecraftInstance instance) throws IOException {
        Path game = instance.gameDirectory().toRealPath();
        Path mods = instance.modsDirectory().toAbsolutePath().normalize();
        Path existing = java.nio.file.Files.exists(mods) ? mods.toRealPath() : mods.getParent().toRealPath().resolve(mods.getFileName()).normalize();
        if (!existing.startsWith(game)) throw new IOException("Mods directory escapes the selected game directory through a link or junction");
        Path state = game.resolve(".mccompanion");
        if (java.nio.file.Files.isSymbolicLink(state)) throw new IOException("Managed state directory must not be a symbolic link");
    }
    public static boolean isSupported(MinecraftInstance value) {
        return (value.loader() == LoaderType.FABRIC && value.minecraftVersion().equals("1.21.1"))
                || (value.loader() == LoaderType.NEOFORGE && value.minecraftVersion().equals("1.21.1"))
                || (value.loader() == LoaderType.FORGE && value.minecraftVersion().equals("1.20.1"));
    }
}
