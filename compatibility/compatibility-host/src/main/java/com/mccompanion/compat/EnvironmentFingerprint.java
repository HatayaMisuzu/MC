package com.mccompanion.compat;

import java.util.Map;

/** Bounded, deterministic identity of one real Minecraft instance. */
public record EnvironmentFingerprint(
        String instanceId,
        String minecraftVersion,
        String loaderType,
        String loaderVersion,
        int javaMajor,
        Map<String, ModFingerprint> mods,
        String configurationHash,
        String scriptsHash,
        String dataPacksHash,
        String modpackHash,
        String digest) {

    public EnvironmentFingerprint {
        instanceId = CompatibilityPack.bounded(instanceId, "instanceId", 1, 128);
        minecraftVersion = CompatibilityPack.bounded(minecraftVersion, "minecraftVersion", 1, 32);
        loaderType = CompatibilityPack.bounded(loaderType, "loaderType", 1, 32)
                .toLowerCase(java.util.Locale.ROOT);
        loaderVersion = CompatibilityPack.bounded(loaderVersion, "loaderVersion", 1, 64);
        if (javaMajor < 8 || javaMajor > 99) throw new IllegalArgumentException("INVALID_JAVA_MAJOR");
        mods = Map.copyOf(mods == null ? Map.of() : mods);
        if (mods.size() > 512) throw new IllegalArgumentException("TOO_MANY_MODS");
        configurationHash = CompatibilityPack.hashOrEmpty(configurationHash, "configurationHash");
        scriptsHash = CompatibilityPack.hashOrEmpty(scriptsHash, "scriptsHash");
        dataPacksHash = CompatibilityPack.hashOrEmpty(dataPacksHash, "dataPacksHash");
        modpackHash = CompatibilityPack.hashOrEmpty(modpackHash, "modpackHash");
        digest = CompatibilityPack.hashOrEmpty(digest, "digest");
        if (digest.isEmpty()) digest = EnvironmentFingerprinter.digest(
                instanceId, minecraftVersion, loaderType, loaderVersion, javaMajor, mods,
                configurationHash, scriptsHash, dataPacksHash, modpackHash);
    }

    public record ModFingerprint(String id, String version, String jarHash) {
        public ModFingerprint {
            id = CompatibilityPack.identifier(id, "mod.id");
            version = CompatibilityPack.bounded(version, "mod.version", 1, 64);
            jarHash = CompatibilityPack.hashOrEmpty(jarHash, "mod.jarHash");
        }
    }
}
