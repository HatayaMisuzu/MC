package com.mccompanion.terminal.hmcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mccompanion.terminal.launcher.*;
import com.mccompanion.terminal.probe.InstanceFactory;
import com.mccompanion.terminal.probe.LogGameDirParser;
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
                    Path logged=new LogGameDirParser().latest(findLogs(launcher.dataDirectory()),id,root).orElse(null);
                    Path running=runningDirectory(root,versionDir).orElse(null);
                    Path game=logged!=null?logged:running!=null?running:isolated?versionDir:root;
                    try { result.add(instances.create(launcher, root, versionDir, game,
                            logged!=null||running!=null?InstanceIsolation.EXPLICIT:isolated ? InstanceIsolation.VERSION_DIRECTORY : InstanceIsolation.MINECRAFT_ROOT,
                            logged!=null||running!=null||isolated ? DetectionConfidence.HIGH : DetectionConfidence.MEDIUM)); }
                    catch (IOException ignored) { }
                }
            } catch (IOException ignored) { }
        }
        return result;
    }
    private static List<Path> findLogs(Path data){List<Path> result=new ArrayList<>();for(Path dir:List.of(data.resolve("logs"),data)){if(Files.isDirectory(dir))try(var files=Files.newDirectoryStream(dir,"*.log")){for(Path p:files)result.add(p);}catch(IOException ignored){}}return result;}
    private static java.util.Optional<Path> runningDirectory(Path root,Path version){for(String name:List.of("hmclversion.json","config.json")){Path file=version.resolve(name);if(Files.isRegularFile(file))try{JsonNode n=JSON.readTree(file.toFile());String value=n.path("runningDirectory").asText("");if(!value.isBlank()){Path p=Path.of(value);return java.util.Optional.of((p.isAbsolute()?p:root.resolve(p)).normalize().toAbsolutePath());}}catch(Exception ignored){}}return java.util.Optional.empty();}

    private static Path findExecutable(Path root) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.exe")) {
            for (Path file : files) if (file.getFileName().toString().toLowerCase(Locale.ROOT).contains("hmcl"))
                return file.toAbsolutePath().normalize();
        } catch (IOException ignored) { }
        return null;
    }
}
