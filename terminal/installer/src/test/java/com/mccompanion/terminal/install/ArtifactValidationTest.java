package com.mccompanion.terminal.install;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mccompanion.protocol.BuildIdentity;
import com.mccompanion.protocol.target.TargetCatalog;
import com.mccompanion.terminal.launcher.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactValidationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    static void artifact(Path path, String minecraft) throws Exception {
        Files.createDirectories(path.getParent());
        var target = TargetCatalog.bundled().byId("fabric-1.21.1").orElseThrow();
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(JSON.writeValueAsBytes(Map.of("id", "minecraft_ai_companion", "version", BuildIdentity.PRODUCT_VERSION,
                    "depends", Map.of("minecraft", "=" + minecraft, "fabricloader", ">=0.19.3", "java", ">=21", "fabric-api", ">=0.116.13"))));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/mcac-target.json"));
            zip.write(JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "productVersion", BuildIdentity.PRODUCT_VERSION, "target", target)));
            zip.closeEntry();
        }
        manifest(path.getParent());
    }
    static void manifest(Path root) throws Exception {
        var files = new ArrayList<Map<String, Object>>(); var targets = new ArrayList<Map<String, String>>();
        try (var stream = Files.list(root)) {
            for (Path path : stream.filter(value -> value.toString().endsWith(".jar")).toList()) {
                files.add(Map.of("path", path.getFileName().toString(), "size", Files.size(path), "sha256", ArtifactValidator.sha256(path)));
                targets.add(Map.of("targetId", "fabric-1.21.1", "artifact", path.getFileName().toString()));
            }
        }
        JSON.writeValue(root.resolve("release-manifest.json").toFile(), Map.of("schemaVersion", 1, "version", BuildIdentity.PRODUCT_VERSION,
                "sourceCommit", "0".repeat(40), "files", files, "targets", targets));
    }
    private MinecraftInstance instance() throws Exception {
        Path game = temp.resolve("game"); Files.createDirectories(game.resolve("mods"));
        try (var zip = new ZipOutputStream(Files.newOutputStream(game.resolve("mods/fabric-api.jar")))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write("{\"id\":\"fabric-api\",\"version\":\"0.116.13+1.21.1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new MinecraftInstance("i", "l", "fixture", temp, temp, game, game.resolve("mods"), game.resolve("config"),
                game.resolve("logs"), "1.21.1", LoaderType.FABRIC, "0.19.3", 21, Optional.empty(), InstanceIsolation.EXPLICIT, DetectionConfidence.HIGH);
    }
    @Test void plansAndInstallsOnlyValidatedReleaseArtifact() throws Exception {
        Path artifact = temp.resolve("release/fabric-1.21.1/mcac.jar"); artifact(artifact, "1.21.1");
        var instance = instance();
        var plan = new InstallPlanner().plan(instance, artifact);
        assertEquals(ArtifactValidator.sha256(artifact), plan.artifactSha256());
        var transaction = new InstallTransaction(); transaction.execute(plan);
        assertTrue(transaction.verify(instance.gameDirectory()));
    }
    @Test void rejectsMissingManifestAndHashMismatch() throws Exception {
        Path artifact = temp.resolve("release/mcac.jar"); artifact(artifact, "1.21.1");
        var target = TargetCatalog.bundled().byId("fabric-1.21.1").orElseThrow();
        Path manifest = artifact.getParent().resolve("release-manifest.json");
        var document = JSON.readTree(manifest.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.path("files").get(0)).put("sha256", "0".repeat(64));
        JSON.writeValue(manifest.toFile(), document);
        assertTrue(assertThrows(java.io.IOException.class, () -> new ArtifactValidator().validate(artifact, target)).getMessage().contains("HASH_MISMATCH"));
        Files.delete(manifest);
        assertTrue(assertThrows(java.io.IOException.class, () -> new ArtifactValidator().validate(artifact, target)).getMessage().contains("MANIFEST_MISSING"));
    }
    @Test void directPlannerAlsoRejectsSubstringMinecraftAndMissingDependencies() throws Exception {
        Path artifact = temp.resolve("release/mcac.jar"); artifact(artifact, "1.21.10"); var instance = instance();
        assertTrue(assertThrows(java.io.IOException.class, () -> new InstallPlanner().plan(instance, artifact)).getMessage().contains("TARGET_MISMATCH"));
        artifact(artifact, "1.21.1"); Files.delete(instance.modsDirectory().resolve("fabric-api.jar"));
        assertTrue(assertThrows(java.io.IOException.class, () -> new InstallPlanner().plan(instance, artifact)).getMessage().contains("DEPENDENCY"));
    }
    @Test void refusesArtifactReplacementAfterPlanWithoutTouchingInstalledFiles() throws Exception {
        Path artifact = temp.resolve("release/mcac.jar"); artifact(artifact, "1.21.1"); var instance = instance();
        var plan = new InstallPlanner().plan(instance, artifact);
        Files.writeString(artifact, "replaced after approval");
        assertEquals("ARTIFACT_CHANGED_AFTER_PLAN", assertThrows(java.io.IOException.class, () -> new InstallTransaction().execute(plan)).getMessage());
        assertFalse(Files.exists(plan.destination()));
        assertFalse(Files.exists(instance.gameDirectory().resolve(".mccompanion/transaction.json")));
    }
}
