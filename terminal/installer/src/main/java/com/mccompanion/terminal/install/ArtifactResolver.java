package com.mccompanion.terminal.install;

import com.mccompanion.terminal.launcher.LoaderType;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import com.mccompanion.terminal.probe.ModJarInspector;

public final class ArtifactResolver {
    public Optional<Path> resolve(MinecraftInstance instance, Path artifactsRoot) throws IOException {
        String target = switch (instance.loader()) {
            case FABRIC -> instance.minecraftVersion().equals("1.21.1") ? "fabric-1.21.1" : null;
            case NEOFORGE -> instance.minecraftVersion().equals("1.21.1") ? "neoforge-1.21.1" : null;
            case FORGE -> instance.minecraftVersion().equals("1.20.1") ? "forge-1.20.1" : null;
            default -> null;
        };
        if (target == null) return Optional.empty();
        List<Path> matches=new ArrayList<>(); ModJarInspector inspector=new ModJarInspector();
        for (Path directory : new Path[]{artifactsRoot.resolve(target), artifactsRoot.resolve("minecraft").resolve(target).resolve("build/libs")}) {
            if (!Files.isDirectory(directory)) continue;
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*.jar")) {
                for (Path jar : jars) {
                    String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.contains("sources") || name.contains("dev") || name.contains("gametest")) continue;
                    try { var info=inspector.inspect(jar); if(info.companion()&&info.metadataType().equals(instance.loader().name().toLowerCase(Locale.ROOT))
                            && info.minecraftRange().contains(instance.minecraftVersion())) matches.add(jar.toAbsolutePath().normalize()); }
                    catch(IOException ignored){ }
                }
            }
        }
        matches=matches.stream().distinct().toList();
        if(matches.size()>1)throw new IOException("Multiple exact Companion artifacts found: "+matches.stream().map(p->p.getFileName().toString()).toList());
        return matches.stream().findFirst();
    }
}
