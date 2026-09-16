package com.mccompanion.terminal.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mccompanion.protocol.BuildIdentity;
import com.mccompanion.protocol.target.TargetDescriptor;
import com.mccompanion.protocol.target.VersionConstraint;
import com.mccompanion.terminal.probe.ModJarInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipFile;

/** Verifies native metadata and the local build/release manifest before planning an install. */
public final class ArtifactValidator {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Validated(Path artifact, String sha256, String productVersion, String targetId) { }

    public Validated validate(Path artifact, TargetDescriptor target) throws IOException {
        Path file = artifact.toRealPath();
        var info = new ModJarInspector().inspect(file);
        if (!info.companion() || !info.metadataType().equals(target.loader())
                || !VersionConstraint.matches(info.minecraftRange(), target.minecraftVersion())
                || !VersionConstraint.matches(info.loaderRange(), target.loaderVersion())) {
            throw new IOException("ARTIFACT_TARGET_MISMATCH: native Minecraft/Loader metadata does not match " + target.targetId());
        }
        if (!BuildIdentity.PRODUCT_VERSION.equals(info.version())) throw new IOException("COMPONENT_UPGRADE_REQUIRED: artifact and Terminal must come from the same release");
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entry = zip.getEntry("META-INF/mcac-target.json");
            if (entry == null || entry.getSize() > 262_144) throw new IOException("ARTIFACT_TARGET_METADATA_MISSING");
            try (var input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(262_145);
                if (bytes.length > 262_144) throw new IOException("ARTIFACT_TARGET_METADATA_TOO_LARGE");
                JsonNode metadata = JSON.readTree(bytes);
                var declared = JSON.treeToValue(metadata.path("target"), TargetDescriptor.class);
                if (!target.equals(declared) || !BuildIdentity.PRODUCT_VERSION.equals(metadata.path("productVersion").asText())) {
                    throw new IOException("ARTIFACT_CATALOG_MISMATCH");
                }
            }
        }
        String hash = sha256(file);
        validateManifest(file, target, hash);
        return new Validated(file, hash, info.version(), target.targetId());
    }

    private static void validateManifest(Path artifact, TargetDescriptor target, String hash) throws IOException {
        Path directory = artifact.getParent();
        for (int depth = 0; directory != null && depth < 8; depth++, directory = directory.getParent()) {
            Path path = directory.resolve("release-manifest.json");
            if (!Files.isRegularFile(path)) continue;
            if (Files.size(path) > 8 * 1024 * 1024) throw new IOException("ARTIFACT_MANIFEST_TOO_LARGE");
            JsonNode manifest = JSON.readTree(path.toFile());
            if (manifest.path("schemaVersion").asInt() != 1
                    || !manifest.path("sourceCommit").asText().matches("[0-9a-f]{40}")
                    || !BuildIdentity.PRODUCT_VERSION.equals(manifest.path("version").asText())) {
                throw new IOException("ARTIFACT_MANIFEST_VERSION_MISMATCH");
            }
            String relative = directory.relativize(artifact).toString().replace('\\', '/');
            int matches = 0;
            for (JsonNode entry : manifest.path("files")) {
                if (!relative.equals(entry.path("path").asText())) continue;
                matches++;
                if (!hash.equals(entry.path("sha256").asText()) || Files.size(artifact) != entry.path("size").asLong(-1)) {
                    throw new IOException("ARTIFACT_HASH_MISMATCH");
                }
            }
            int targets = 0;
            for (JsonNode entry : manifest.path("targets")) {
                if (target.targetId().equals(entry.path("targetId").asText())
                        && relative.equals(entry.path("artifact").asText())) targets++;
            }
            if (matches != 1 || targets != 1) throw new IOException("ARTIFACT_NOT_IN_MANIFEST");
            return;
        }
        throw new IOException("ARTIFACT_MANIFEST_MISSING: use the complete MCAC release or target build output");
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
