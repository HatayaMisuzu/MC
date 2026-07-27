package com.mccompanion.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Produces hashes only from explicitly supplied, bounded instance inputs. */
public final class EnvironmentFingerprinter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_HASHED_FILE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FILES_PER_GROUP = 512;

    public EnvironmentFingerprint fingerprint(
            Path instanceRoot,
            String instanceId,
            String minecraftVersion,
            String loaderType,
            String loaderVersion,
            int javaMajor,
            Map<String, ModInput> mods,
            List<Path> configuration,
            List<Path> scripts,
            List<Path> dataPacks,
            Path modpackManifest) throws IOException {
        Path root = instanceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Map<String, EnvironmentFingerprint.ModFingerprint> fingerprints = new LinkedHashMap<>();
        if (mods != null) {
            if (mods.size() > MAX_FILES_PER_GROUP) throw new IOException("TOO_MANY_MODS");
            for (Map.Entry<String, ModInput> entry : mods.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                ModInput mod = entry.getValue();
                Path jar = safeRegular(root, mod.jar());
                fingerprints.put(entry.getKey(), new EnvironmentFingerprint.ModFingerprint(
                        entry.getKey(), mod.version(), CompatibilityPackLoader.sha256(jar)));
            }
        }
        return new EnvironmentFingerprint(instanceId, minecraftVersion, loaderType, loaderVersion,
                javaMajor, fingerprints, groupHash(root, configuration), groupHash(root, scripts),
                groupHash(root, dataPacks),
                modpackManifest == null ? "" : CompatibilityPackLoader.sha256(safeRegular(root, modpackManifest)),
                "");
    }

    static String digest(
            String instanceId,
            String minecraftVersion,
            String loaderType,
            String loaderVersion,
            int javaMajor,
            Map<String, EnvironmentFingerprint.ModFingerprint> modValues,
            String configurationHash,
            String scriptsHash,
            String dataPacksHash,
            String modpackHash) {
        ObjectNode root = JSON.createObjectNode()
                .put("instanceId", instanceId)
                .put("minecraftVersion", minecraftVersion)
                .put("loaderType", loaderType)
                .put("loaderVersion", loaderVersion)
                .put("javaMajor", javaMajor)
                .put("configurationHash", configurationHash)
                .put("scriptsHash", scriptsHash)
                .put("dataPacksHash", dataPacksHash)
                .put("modpackHash", modpackHash);
        ObjectNode mods = root.putObject("mods");
        modValues.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                mods.putObject(entry.getKey()).put("version", entry.getValue().version())
                        .put("jarHash", entry.getValue().jarHash()));
        try {
            return CompatibilityPackLoader.sha256(JSON.writeValueAsBytes(root));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String groupHash(Path root, List<Path> paths) throws IOException {
        if (paths == null || paths.isEmpty()) return "";
        if (paths.size() > MAX_FILES_PER_GROUP) throw new IOException("TOO_MANY_FINGERPRINT_FILES");
        ObjectNode values = JSON.createObjectNode();
        for (Path requested : paths.stream().map(Path::normalize).sorted(Comparator.comparing(Path::toString)).toList()) {
            Path file = safeRegular(root, requested);
            values.put(root.relativize(file).toString().replace('\\', '/'),
                    CompatibilityPackLoader.sha256(file));
        }
        return CompatibilityPackLoader.sha256(JSON.writeValueAsBytes(values));
    }

    private static Path safeRegular(Path root, Path requested) throws IOException {
        if (requested == null || requested.isAbsolute()) throw new IOException("FINGERPRINT_PATH_INVALID");
        Path candidate = root.resolve(requested).normalize();
        if (!candidate.startsWith(root) || Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("FINGERPRINT_PATH_ESCAPES_INSTANCE");
        }
        Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(root) || Files.size(real) > MAX_HASHED_FILE_BYTES) {
            throw new IOException("FINGERPRINT_FILE_INVALID");
        }
        return real;
    }

    public record ModInput(String version, Path jar) {
        public ModInput {
            version = CompatibilityPack.bounded(version, "mod.version", 1, 64);
            java.util.Objects.requireNonNull(jar, "jar");
        }
    }
}
