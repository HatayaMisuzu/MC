package com.mccompanion.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class CompatibilityPackFixture {
    private CompatibilityPackFixture() {}

    static Path pack(Path directory, String id, String version, String type,
                     String minecraft, String loader, String modId, String modHash,
                     List<String> dependencies, List<String> conflicts,
                     String capabilityId, String risk, String contractValue) throws IOException {
        StringBuilder modTarget = new StringBuilder();
        if (modId != null && !modId.isBlank()) {
            modTarget.append("  mods:\n    - id: ").append(modId).append("\n");
            if (modHash != null && !modHash.isBlank()) {
                modTarget.append("      jarHash: ").append(modHash).append("\n");
            }
        }
        StringBuilder manifest = new StringBuilder("""
                schemaVersion: mcac-compat/1
                pack:
                  id: %s
                  version: %s
                  type: %s
                  displayName: Fixture %s
                  authorType: external-agent
                  createdAt: 2026-07-27T00:00:00Z
                target:
                  minecraft:
                    exact: %s
                  loader:
                    type: %s
                    versionRange: ""
                %s
                runtime:
                  minimumHostVersion: 1
                  nativeCode: false
                  hotReloadable: true
                  restartRequired: false
                permissions:
                  declared:
                    - REGISTRY_READ
                    - WORLD_OBSERVE
                  forbidden:
                    - SHELL
                    - ARBITRARY_FILE_ACCESS
                    - DIRECT_WORLD_EDIT
                    - CREDENTIAL_READ
                    - PROCESS_EXEC
                    - NETWORK_UNBOUNDED
                """.formatted(id, version, type, id, minecraft, loader, modTarget));
        manifest.append("dependencies:\n");
        dependencies.forEach(value -> manifest.append("  - ").append(value).append('\n'));
        manifest.append("conflicts:\n");
        conflicts.forEach(value -> manifest.append("  - ").append(value).append('\n'));
        manifest.append("replaces: []\nextends: []\npatches: []\nprecedence: 0\nlimitations: []\n");
        String capabilities = """
                {
                  "schemaVersion": "mcac-capabilities/1",
                  "capabilities": [{
                    "id": "%s",
                    "kind": "tool",
                    "risk": "%s",
                    "enabled": true,
                    "contract": {"value": "%s"}
                  }]
                }
                """.formatted(capabilityId, risk, contractValue);
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.yaml", manifest.toString().getBytes(StandardCharsets.UTF_8));
        entries.put("capabilities/tools.json", capabilities.getBytes(StandardCharsets.UTF_8));
        entries.put("capabilities/observations.yaml",
                "schemaVersion: mcac-observations/1\nobservations: []\n".getBytes(StandardCharsets.UTF_8));
        entries.put("capabilities/actions.yaml",
                "schemaVersion: mcac-actions/1\nactions: []\n".getBytes(StandardCharsets.UTF_8));
        entries.put("capabilities/safety.yaml",
                "schemaVersion: mcac-safety/1\nrules: []\n".getBytes(StandardCharsets.UTF_8));
        entries.put("evidence/limitations.json",
                "{\"schemaVersion\":\"mcac-limitations/1\",\"limitations\":[]}".getBytes(StandardCharsets.UTF_8));
        return archive(directory.resolve(id + '-' + version + ".mcac-compat"), entries);
    }

    static Path archive(Path output, Map<String, byte[]> content) throws IOException {
        Files.createDirectories(output.getParent());
        StringBuilder sums = new StringBuilder();
        content.forEach((name, bytes) -> sums.append(CompatibilityPackLoader.sha256(bytes))
                .append("  ").append(name).append('\n'));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : content.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("SHA256SUMS.txt"));
            zip.write(sums.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output;
    }

    static EnvironmentFingerprint environment(String configHash) {
        String modHash = "a".repeat(64);
        return new EnvironmentFingerprint("instance-a", "1.21.1", "fabric", "0.19.3", 21,
                Map.of("fixturemod", new EnvironmentFingerprint.ModFingerprint(
                        "fixturemod", "1.0.0", modHash)),
                configHash == null ? "" : configHash, "", "", "", "");
    }

    static CompatibilityGrant grant(Path store) {
        return new CompatibilityGrant("grant-a", "codex-agent", "terminal-controller",
                "profile-a", "instance-a", store, CompatibilityGrant.KNOWN_OPERATIONS,
                Instant.now().plusSeconds(3600), CompatibilityPack.Risk.CRITICAL);
    }
}
