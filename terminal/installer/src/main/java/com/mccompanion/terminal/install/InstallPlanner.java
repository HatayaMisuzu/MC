package com.mccompanion.terminal.install;

import com.mccompanion.terminal.launcher.LoaderType;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.launcher.DetectionConfidence;
import com.mccompanion.terminal.probe.ModJarInspector;
import com.mccompanion.protocol.target.TargetCatalog;
import com.mccompanion.protocol.target.TargetDescriptor;
import com.mccompanion.protocol.target.VersionConstraint;
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
        TargetDescriptor target = target(instance).orElseThrow();
        List<String> issues = environmentIssues(instance);
        if (!issues.isEmpty()) throw new IOException(String.join("; ", issues));
        ArtifactValidator.Validated validated = new ArtifactValidator().validate(artifact, target);
        List<Path> old = new ArrayList<>();
        for (ModJarInspector.ModInfo mod : inspector.inspectDirectory(instance.modsDirectory())) {
            if (mod.companion()) old.add(mod.jar());
        }
        Path destination = instance.modsDirectory().resolve(artifact.getFileName()).normalize();
        if (!destination.startsWith(instance.modsDirectory().toAbsolutePath().normalize())) throw new IOException("Unsafe destination");
        return new InstallPlan(instance, artifact.toAbsolutePath().normalize(), destination, old,
                false, Long.toUnsignedString(Instant.now().toEpochMilli(), 36), validated.sha256());
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
        return target(value).isPresent();
    }
    public static java.util.Optional<TargetDescriptor> target(MinecraftInstance value) {
        return TargetCatalog.bundled().find(value.minecraftVersion(), value.loader().name());
    }
    public static List<String> environmentIssues(MinecraftInstance instance) {
        TargetDescriptor target = target(instance).orElse(null);
        if (target == null) return List.of("TARGET_UNSUPPORTED");
        List<String> issues = new ArrayList<>();
        if (!target.supportsLoader(instance.loaderVersion())) issues.add("LOADER_VERSION_UNSUPPORTED: " + target.loaderVersionRange());
        if (instance.requiredJavaMajor() < target.javaMinimum()) issues.add("JAVA_REQUIREMENT_UNCONFIRMED: " + target.javaMinimum());
        var installed = new ModJarInspector().inspectDirectory(instance.modsDirectory());
        target.dependencies().forEach((id, constraint) -> {
            var matches = installed.stream().filter(mod -> mod.id().equals(id)).toList();
            if (matches.size() != 1 || !VersionConstraint.matches(constraint, matches.get(0).version())) {
                issues.add("DEPENDENCY_MISSING_OR_INCOMPATIBLE: " + id + " " + constraint);
            }
        });
        return List.copyOf(issues);
    }
}
