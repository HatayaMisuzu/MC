package com.mccompanion.terminal.install;

import com.mccompanion.terminal.launcher.LoaderType;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

public final class ArtifactResolver {
    public Optional<Path> resolve(MinecraftInstance instance, Path artifactsRoot) throws IOException {
        String target = switch (instance.loader()) {
            case FABRIC -> instance.minecraftVersion().equals("1.21.1") ? "fabric-1.21.1" : null;
            case NEOFORGE -> instance.minecraftVersion().equals("1.21.1") ? "neoforge-1.21.1" : null;
            case FORGE -> instance.minecraftVersion().equals("1.20.1") ? "forge-1.20.1" : null;
            default -> null;
        };
        if (target == null) return Optional.empty();
        for (Path directory : new Path[]{artifactsRoot.resolve(target), artifactsRoot.resolve("minecraft").resolve(target).resolve("build/libs")}) {
            if (!Files.isDirectory(directory)) continue;
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*.jar")) {
                for (Path jar : jars) {
                    String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!name.contains("sources") && !name.contains("dev-shadow") && !name.contains("gametest"))
                        return Optional.of(jar.toAbsolutePath().normalize());
                }
            }
        }
        return Optional.empty();
    }
}
