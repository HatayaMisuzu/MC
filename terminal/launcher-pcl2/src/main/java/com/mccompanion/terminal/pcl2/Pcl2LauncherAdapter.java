package com.mccompanion.terminal.pcl2;

import com.mccompanion.terminal.launcher.*;
import com.mccompanion.terminal.probe.InstanceFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Pcl2LauncherAdapter implements LauncherAdapter {
    private final Pcl2SetupParser setup = new Pcl2SetupParser();
    private final InstanceFactory instances = new InstanceFactory();
    @Override public LauncherType type() { return LauncherType.PCL2; }

    @Override public List<LauncherInstallation> discover(DiscoveryContext context) {
        List<LauncherInstallation> found = new ArrayList<>();
        for (Path supplied : context.searchRoots()) {
            Path root = Files.isDirectory(supplied) ? supplied : supplied.toAbsolutePath().getParent();
            if (root == null || !Files.isDirectory(root.resolve("PCL"))) continue;
            Path executable = findExecutable(root);
            Path config = root.resolve("PCL").resolve("Setup.ini");
            if (executable == null || !Files.isRegularFile(config)) continue;
            try {
                Map<String, String> values = setup.parse(config);
                Path minecraft = setup.resolveMinecraftRoot(root, values.get("LaunchFolderSelect"));
                Map<String, String> evidence = new LinkedHashMap<>();
                evidence.put("setup", config.toString());
                evidence.put("minecraftRoot", minecraft.toString());
                String version = detectVersion(root.resolve("PCL").resolve("Log1.txt"));
                found.add(new LauncherInstallation("pcl2-" + InstanceFactory.stableId("pcl2", root),
                        type(), version, executable, root.resolve("PCL"), List.of(minecraft),
                        DetectionConfidence.HIGH, evidence));
            } catch (IOException ignored) { /* malformed installation is evidence for doctor, not a scan crash */ }
        }
        return found.stream().distinct().toList();
    }

    @Override public List<MinecraftInstance> discoverInstances(LauncherInstallation launcher) {
        List<MinecraftInstance> result = new ArrayList<>();
        for (Path root : launcher.minecraftRoots()) {
            Path versions = root.resolve("versions");
            if (!Files.isDirectory(versions)) continue;
            try (DirectoryStream<Path> directories = Files.newDirectoryStream(versions, Files::isDirectory)) {
                for (Path versionDir : directories) {
                    String versionId = versionDir.getFileName().toString();
                    if (!Files.isRegularFile(versionDir.resolve(versionId + ".json"))) continue;
                    try {
                        Map<String, String> versionSetup = setup.parse(versionDir.resolve("PCL").resolve("Setup.ini"));
                        boolean isolated = "true".equalsIgnoreCase(versionSetup.get("VersionArgumentIndieV2"))
                                || InstanceFactory.looksIsolated(versionDir);
                        Path gameDir = isolated ? versionDir : root;
                        result.add(instances.create(launcher, root, versionDir, gameDir,
                                isolated ? InstanceIsolation.VERSION_DIRECTORY : InstanceIsolation.MINECRAFT_ROOT,
                                isolated ? DetectionConfidence.HIGH : DetectionConfidence.MEDIUM));
                    } catch (IOException ignored) { }
                }
            } catch (IOException ignored) { }
        }
        return result;
    }

    private static Path findExecutable(Path root) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.exe")) {
            for (Path file : files) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.contains("plain craft launcher") || name.startsWith("pcl")) return file.toAbsolutePath().normalize();
            }
        } catch (IOException ignored) { }
        return null;
    }
    private static String detectVersion(Path log) {
        if (!Files.isRegularFile(log)) return "unknown";
        try {
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                int at = line.indexOf("程序版本");
                if (at >= 0) return line.substring(at).replace("程序版本", "").replace(":", "").replace("：", "").strip();
            }
        } catch (IOException ignored) { }
        return "unknown";
    }
}
