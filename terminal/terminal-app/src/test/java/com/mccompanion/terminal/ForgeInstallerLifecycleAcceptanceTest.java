package com.mccompanion.terminal;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mccompanion.terminal.install.InstallPlan;
import com.mccompanion.terminal.install.InstallPlanner;
import com.mccompanion.terminal.install.InstallTransaction;
import com.mccompanion.terminal.launcher.DetectionConfidence;
import com.mccompanion.terminal.launcher.InstanceIsolation;
import com.mccompanion.terminal.launcher.LoaderType;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.runtime.PairingService;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Clean-fixture acceptance for the Forge installer path. This deliberately uses
 * the production planner, transaction, pairing, and uninstall coordinators.
 */
class ForgeInstallerLifecycleAcceptanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @Test void completesForgeInstallRepairUpdateRollbackAndBothUninstallPolicies() throws Exception {
        Path game = temp.resolve("PCL2/.minecraft/versions/Forge验收");
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        MinecraftInstance instance = new MinecraftInstance(
                "forge-acceptance", "pcl2", "Forge 验收", temp, temp, game,
                mods, game.resolve("config"), game.resolve("logs"), "1.20.1",
                LoaderType.FORGE, "47.4.10", 17, Optional.empty(),
                InstanceIsolation.VERSION_DIRECTORY, DetectionConfidence.HIGH);
        Path world = game.resolve("saves/acceptance/level.dat");
        Path unrelated = mods.resolve("unrelated.jar");
        Files.createDirectories(world.getParent());
        Files.writeString(world, "world");
        Files.writeString(unrelated, "unrelated");

        Path artifacts = temp.resolve("release/artifacts");
        Path v1 = forgeArtifact(artifacts.resolve("forge-1.20.1/mcac-forge-v1.jar"), com.mccompanion.protocol.BuildIdentity.PRODUCT_VERSION);
        InstallService installService = new InstallService();
        InstallPlan initial = installService.plan(instance, artifacts);
        assertEquals(v1.toAbsolutePath().normalize(), initial.artifact());
        assertEquals(mods.resolve(v1.getFileName()), initial.destination());
        assertFalse(initial.fabricApiMissing());
        InstallTransaction transaction = new InstallTransaction();
        transaction.execute(initial);
        assertTrue(transaction.verify(game));

        Path controlHome = temp.resolve("control");
        Path profileDirectory = controlHome.resolve("profiles/forge-acceptance");
        RuntimeProfile profile = new RuntimeProfile(
                "forge-acceptance", profileDirectory, temp.resolve("runtime.cmd"), 8766, 18766);
        new PairingService().ensureConfigured(instance, profile);
        Path runtimeConfig = game.resolve("config/minecraft-ai-companion/runtime.json");
        var config = JSON.readTree(runtimeConfig.toFile());
        assertTrue(config.path("enabled").asBoolean());
        assertEquals("forge-acceptance", config.path("instanceId").asText());
        assertEquals("ws://127.0.0.1:8766", config.path("runtimeUrl").asText());

        Files.writeString(initial.destination(), "damaged");
        assertFalse(transaction.verify(game));
        InstallPlan repair = new InstallPlan(
                instance, v1, initial.destination(), java.util.List.of(), false, "forge-repair");
        transaction.execute(repair);
        assertTrue(transaction.verify(game));

        Path v2 = forgeArtifact(temp.resolve("mcac-forge-v2.jar"), com.mccompanion.protocol.BuildIdentity.PRODUCT_VERSION);
        InstallPlan update = new InstallPlanner().plan(instance, v2);
        update = new InstallPlan(
                instance, update.artifact(), update.destination(), update.replacedFiles(),
                update.fabricApiMissing(), "forge-update");
        transaction.execute(update);
        assertTrue(transaction.verify(game));
        assertTrue(Files.exists(world));
        assertTrue(Files.exists(unrelated));

        transaction.rollback(game, "forge-update");
        assertArrayEquals(Files.readAllBytes(v1), Files.readAllBytes(initial.destination()));
        assertTrue(Files.exists(world));
        assertTrue(Files.exists(unrelated));

        InstallPlan preserveInstall = new InstallPlan(
                instance, v2, initial.destination(), java.util.List.of(initial.destination()),
                false, "forge-preserve-uninstall");
        transaction.execute(preserveInstall);
        AtomicInteger stops = new AtomicInteger();
        InstanceUninstallService uninstall = new InstanceUninstallService(
                ignored -> stops.incrementAndGet(), transaction);
        uninstall.uninstall(
                instance, profile, controlHome, InstanceUninstallService.DataPolicy.PRESERVE);
        assertEquals(1, stops.get());
        assertFalse(Files.exists(initial.destination()));
        assertTrue(Files.exists(runtimeConfig));
        assertTrue(Files.exists(profileDirectory.resolve("pairing.token")));
        assertTrue(Files.exists(world));
        assertTrue(Files.exists(unrelated));

        InstallPlan deleteInstall = new InstallPlan(
                instance, v2, initial.destination(), java.util.List.of(),
                false, "forge-delete-uninstall");
        transaction.execute(deleteInstall);
        uninstall.uninstall(
                instance, profile, controlHome, InstanceUninstallService.DataPolicy.DELETE);
        assertEquals(2, stops.get());
        assertFalse(Files.exists(initial.destination()));
        assertFalse(Files.exists(game.resolve(".mccompanion")));
        assertFalse(Files.exists(game.resolve("config/minecraft-ai-companion")));
        assertFalse(Files.exists(profileDirectory));
        assertTrue(Files.exists(world));
        assertTrue(Files.exists(unrelated));
    }

    private static Path forgeArtifact(Path path, String version) throws Exception {
        Files.createDirectories(path.getParent());
        String metadata = """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="All Rights Reserved"
                [[mods]]
                modId="minecraft_ai_companion"
                version="%s"
                [[dependencies.minecraft_ai_companion]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,1.20.2)"
                ordering="NONE"
                side="BOTH"
                [[dependencies.minecraft_ai_companion]]
                modId="forge"
                mandatory=true
                versionRange="[47.4.10,48)"
                ordering="NONE"
                side="BOTH"
                """.formatted(version);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("META-INF/mods.toml"));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/mcac-target.json"));
            zip.write(JSON.writeValueAsBytes(java.util.Map.of("schemaVersion", 1, "productVersion", version,
                    "target", com.mccompanion.protocol.target.TargetCatalog.bundled().byId("forge-1.20.1").orElseThrow())));
            zip.closeEntry();
        }
        JSON.writeValue(path.getParent().resolve("release-manifest.json").toFile(), java.util.Map.of(
                "schemaVersion", 1, "version", version, "sourceCommit", "0".repeat(40),
                "files", java.util.List.of(java.util.Map.of("path", path.getFileName().toString(), "size", Files.size(path),
                        "sha256", com.mccompanion.terminal.install.ArtifactValidator.sha256(path))),
                "targets", java.util.List.of(java.util.Map.of("targetId", "forge-1.20.1", "artifact", path.getFileName().toString()))));
        return path;
    }
}
