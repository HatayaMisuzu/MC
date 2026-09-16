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
        var descriptor = InstallPlanner.target(instance).orElse(null);
        String target = descriptor == null ? null : descriptor.targetId();
        if (target == null) return Optional.empty();
        List<Path> matches=new ArrayList<>(); List<String> failures = new ArrayList<>();
        for (Path directory : new Path[]{artifactsRoot.resolve(target), artifactsRoot.resolve("minecraft").resolve(target).resolve("build/libs")}) {
            if (!Files.isDirectory(directory)) continue;
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*.jar")) {
                for (Path jar : jars) {
                    String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.contains("sources") || name.contains("dev") || name.contains("gametest")) continue;
                    try { matches.add(new ArtifactValidator().validate(jar, descriptor).artifact()); }
                    catch(IOException invalid){ failures.add(jar.getFileName() + ": " + invalid.getMessage()); }
                }
            }
        }
        matches=matches.stream().distinct().toList();
        if(matches.size()>1)throw new IOException("Multiple exact Companion artifacts found: "+matches.stream().map(p->p.getFileName().toString()).toList());
        if(matches.isEmpty() && !failures.isEmpty()) throw new IOException(String.join("; ", failures));
        return matches.stream().findFirst();
    }
}
