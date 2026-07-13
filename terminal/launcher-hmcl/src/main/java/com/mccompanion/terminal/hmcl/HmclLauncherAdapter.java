package com.mccompanion.terminal.hmcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mccompanion.terminal.launcher.*;
import com.mccompanion.terminal.probe.InstanceFactory;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HmclLauncherAdapter implements LauncherAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final InstanceFactory instances = new InstanceFactory();
    @Override public LauncherType type() { return LauncherType.HMCL; }

    @Override public List<LauncherInstallation> discover(DiscoveryContext context) {
        List<LauncherInstallation> result = new ArrayList<>();
        for (Path supplied : context.searchRoots()) {
            Path root = Files.isDirectory(supplied) ? supplied : supplied.toAbsolutePath().getParent();
            if (root == null) continue;
            Path executable = findExecutable(root);
            Path config = root.resolve(".hmcl").resolve("hmcl.json");
            if (executable == null || !Files.isRegularFile(config)) continue;
            try {
                JsonNode document = JSON.readTree(config.toFile());
                Set<Path> roots = new LinkedHashSet<>();
                JsonNode configurations = document.path("configurations");
                configurations.fields().forEachRemaining(entry -> {
                    JsonNode value = entry.getValue();
                    String gameDir = value.path("gameDir").asText("");
                    if (!gameDir.isBlank()) {
                        Path parsed = Path.of(gameDir.replace('\\', '/'));
                        roots.add((parsed.isAbsolute() ? parsed : root.resolve(parsed)).toAbsolutePath().normalize());
                    }
                });
                if (roots.isEmpty()) roots.add(root.resolve(".minecraft").toAbsolutePath().normalize());
                result.add(new LauncherInstallation("hmcl-" + InstanceFactory.stableId("hmcl", root), type(),
                        document.path("version").asText("unknown"), executable, root.resolve(".hmcl"),
                        List.copyOf(roots), DetectionConfidence.HIGH, java.util.Map.of("config", config.toString())));
            } catch (IOException | RuntimeException ignored) { }
        }
        return result;
    }

    @Override public List<MinecraftInstance> discoverInstances(LauncherInstallation launcher) {
        List<MinecraftInstance> result = new ArrayList<>();
        for (Path root : launcher.minecraftRoots()) {
            Path versions = root.resolve("versions");
            if (!Files.isDirectory(versions)) continue;
            try (DirectoryStream<Path> directories = Files.newDirectoryStream(versions, Files::isDirectory)) {
                for (Path versionDir : directories) {
                    String id = versionDir.getFileName().toString();
                    if (!Files.isRegularFile(versionDir.resolve(id + ".json"))) continue;
                    boolean isolated = InstanceFactory.looksIsolated(versionDir);
                    try { result.add(instances.create(launcher, root, versionDir, isolated ? versionDir : root,
                            isolated ? InstanceIsolation.VERSION_DIRECTORY : InstanceIsolation.MINECRAFT_ROOT,
                            isolated ? DetectionConfidence.HIGH : DetectionConfidence.MEDIUM)); }
                    catch (IOException ignored) { }
                }
            } catch (IOException ignored) { }
        }
        return result;
    }

    private static Path findExecutable(Path root) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.exe")) {
            for (Path file : files) if (file.getFileName().toString().toLowerCase(Locale.ROOT).contains("hmcl"))
                return file.toAbsolutePath().normalize();
        } catch (IOException ignored) { }
        return null;
    }
}
