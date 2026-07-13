package com.mccompanion.terminal.probe;

import com.mccompanion.terminal.launcher.DetectionConfidence;
import com.mccompanion.terminal.launcher.InstanceIsolation;
import com.mccompanion.terminal.launcher.LauncherInstallation;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

public final class InstanceFactory {
    private final VersionJsonResolver resolver = new VersionJsonResolver();

    public MinecraftInstance create(LauncherInstallation launcher, Path root, Path versionDir,
                                    Path gameDir, InstanceIsolation isolation,
                                    DetectionConfidence confidence) throws IOException {
        root = root.toAbsolutePath().normalize();
        versionDir = versionDir.toAbsolutePath().normalize();
        gameDir = gameDir.toAbsolutePath().normalize();
        if (!versionDir.startsWith(root.resolve("versions").normalize())) throw new IOException("Version directory escaped root");
        String id = versionDir.getFileName().toString();
        VersionJsonResolver.ResolvedVersion version = resolver.resolve(root, id);
        return new MinecraftInstance(stableId(launcher.launcherId(), gameDir), launcher.launcherId(), id,
                root, versionDir, gameDir, gameDir.resolve("mods"), gameDir.resolve("config"), gameDir.resolve("logs"),
                version.minecraftVersion(), version.loader(), version.loaderVersion(), version.requiredJavaMajor(),
                Optional.empty(), isolation, confidence);
    }

    public static String stableId(String launcherId, Path gameDir) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (launcherId + "\n" + gameDir.toAbsolutePath().normalize()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static boolean looksIsolated(Path versionDir) {
        return Files.isDirectory(versionDir.resolve("mods")) || Files.isDirectory(versionDir.resolve("config"))
                || Files.isDirectory(versionDir.resolve("saves"));
    }
}
