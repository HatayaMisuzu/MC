package com.mccompanion.protocol.target;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Build expectation, not permission to execute and not verification evidence. */
public record TargetDescriptor(String targetId, String minecraftVersion, String loader,
        String loaderVersion, String loaderVersionRange, String loaderMetadataRange,
        int javaMinimum, String bridgeMode, String protocol,
        Map<String, String> dependencies, List<String> expectedCapabilities,
        Map<String, String> limits, String lifecycle, String buildDirectory,
        String gameTestTask, String runtimeE2eTask, String persistenceTask) {
    public TargetDescriptor {
        Objects.requireNonNull(targetId); Objects.requireNonNull(minecraftVersion); Objects.requireNonNull(loader);
        if (!targetId.matches("[a-z]+-[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")
                || !targetId.equals(loader + "-" + minecraftVersion)) throw new IllegalArgumentException("Invalid target identity");
        if (!VersionConstraint.matches("=" + minecraftVersion, minecraftVersion)
                || !VersionConstraint.matches(loaderVersionRange, loaderVersion)) throw new IllegalArgumentException("Invalid target versions");
        if (javaMinimum < 17 || !List.of("FULL", "LOCAL_ONLY").contains(bridgeMode)) throw new IllegalArgumentException("Invalid target mode/toolchain");
        if (!Objects.equals(buildDirectory, "minecraft/" + targetId)) throw new IllegalArgumentException("Invalid target build directory");
        dependencies = Map.copyOf(dependencies); expectedCapabilities = List.copyOf(expectedCapabilities); limits = Map.copyOf(limits);
        if (expectedCapabilities.stream().distinct().count() != expectedCapabilities.size()) throw new IllegalArgumentException("Duplicate expected capability");
        if (!fullBridge(bridgeMode) && (!expectedCapabilities.isEmpty() || protocol != null)) throw new IllegalArgumentException("LOCAL_ONLY cannot declare a Runtime protocol/capabilities");
        if (fullBridge(bridgeMode) && !com.mccompanion.protocol.BuildIdentity.PROTOCOL.equals(protocol)) throw new IllegalArgumentException("Unsupported target protocol");
    }
    private static boolean fullBridge(String mode) { return "FULL".equals(mode); }
    public boolean fullBridge() { return fullBridge(bridgeMode); }
    public boolean matches(String minecraft, String loaderName) {
        return minecraftVersion.equals(minecraft) && loader.equals(loaderName == null ? "" : loaderName.toLowerCase(Locale.ROOT));
    }
    public boolean supportsLoader(String version) { return VersionConstraint.matches(loaderVersionRange, version); }
}
