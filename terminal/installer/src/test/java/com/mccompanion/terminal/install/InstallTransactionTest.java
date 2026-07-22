package com.mccompanion.terminal.install;

import com.mccompanion.terminal.launcher.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InstallTransactionTest {
    @TempDir Path temp;
    private MinecraftInstance instance() {
        Path game = temp.resolve("game");
        return new MinecraftInstance("one","pcl2-one","Test",temp,temp.resolve("versions/Test"),game,
                game.resolve("mods"),game.resolve("config"),game.resolve("logs"),"1.21.1",LoaderType.FABRIC,
                "0.16",21, Optional.empty(),InstanceIsolation.VERSION_DIRECTORY,DetectionConfidence.HIGH);
    }
    @Test void installAndRollbackPreserveExistingProjectJar() throws Exception {
        MinecraftInstance instance=instance();Files.createDirectories(instance.modsDirectory());
        Path old=instance.modsDirectory().resolve("old.jar");Files.writeString(old,"old");
        Path artifact=temp.resolve("new.jar");Files.writeString(artifact,"new");
        InstallPlan plan=new InstallPlan(instance,artifact,instance.modsDirectory().resolve("new.jar"),List.of(old),false,"point-one");
        InstallTransaction transaction=new InstallTransaction();var result=transaction.execute(plan);
        assertEquals("new",Files.readString(result.installedFile()));assertFalse(Files.exists(old));
        transaction.rollback(instance.gameDirectory(),"point-one");
        assertEquals("old",Files.readString(old));assertFalse(Files.exists(result.installedFile()));
    }
    @Test void unsupportedVersionIsBlockedBeforeArtifactInspection() {
        MinecraftInstance value=new MinecraftInstance("x","l","x",temp,temp,temp,temp,temp,temp,"26.2",
                LoaderType.VANILLA,"",25,Optional.empty(),InstanceIsolation.VERSION_DIRECTORY,DetectionConfidence.HIGH);
        assertFalse(InstallPlanner.isSupported(value));
    }
    @Test void uninstallRefusesToDeleteModifiedManagedJar() throws Exception {
        MinecraftInstance instance=instance();Files.createDirectories(instance.modsDirectory());
        Path artifact=temp.resolve("new.jar");Files.writeString(artifact,"new");
        InstallPlan plan=new InstallPlan(instance,artifact,instance.modsDirectory().resolve("new.jar"),List.of(),false,"point-two");
        InstallTransaction transaction=new InstallTransaction();transaction.execute(plan);
        Files.writeString(plan.destination(),"user-modified");
        assertThrows(java.io.IOException.class,()->transaction.uninstall(instance.gameDirectory()));
        assertTrue(Files.exists(plan.destination()));
    }
    @Test void legacyManifestSchemaStillVerifies() throws Exception {
        MinecraftInstance instance=instance();Files.createDirectories(instance.modsDirectory());
        Path jar=instance.modsDirectory().resolve("managed.jar");Files.writeString(jar,"managed");
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        Path state=instance.gameDirectory().resolve(".mccompanion");Files.createDirectories(state);
        Files.writeString(state.resolve("install-manifest.json"),"{\"schemaVersion\":1,\"installedFile\":\"mods/managed.jar\",\"sha256\":\""+hash+"\"}");
        assertTrue(new InstallTransaction().verify(instance.gameDirectory()));
    }

    @Test void updatePreservesUserDataAndFailureRestoresArtifactAndManifest() throws Exception {
        MinecraftInstance instance=instance();Files.createDirectories(instance.modsDirectory());
        Path v1=temp.resolve("v1.jar");Files.writeString(v1,"version-one");
        Path managed=instance.modsDirectory().resolve("mcac.jar");
        InstallTransaction transaction=new InstallTransaction();
        transaction.execute(new InstallPlan(instance,v1,managed,List.of(),false,"v1"));
        Path manifest=instance.gameDirectory().resolve(".mccompanion/install-manifest.json");
        byte[] v1Manifest=Files.readAllBytes(manifest);

        Path config=instance.configDirectory().resolve("mcac/settings.json");
        Path database=instance.gameDirectory().resolve(".mccompanion/user-data/companion.db");
        Path workspace=instance.gameDirectory().resolve(".mccompanion/user-data/workspace/skill.yaml");
        Path identity=instance.gameDirectory().resolve(".mccompanion/user-data/identity.json");
        for(Path file:List.of(config,database,workspace,identity)){Files.createDirectories(file.getParent());Files.writeString(file,"preserve");}
        Path world=instance.gameDirectory().resolve("saves/world/level.dat");Files.createDirectories(world.getParent());Files.writeString(world,"world");
        Path unrelated=instance.modsDirectory().resolve("unrelated.jar");Files.writeString(unrelated,"other");

        Path v2=temp.resolve("v2.jar");Files.writeString(v2,"version-two");
        transaction.execute(new InstallPlan(instance,v2,managed,List.of(managed),false,"v2"));
        assertEquals("version-two",Files.readString(managed));
        for(Path file:List.of(config,database,workspace,identity,world,unrelated))assertTrue(Files.exists(file),file.toString());
        byte[] v2Manifest=Files.readAllBytes(manifest);
        assertFalse(java.util.Arrays.equals(v1Manifest,v2Manifest));

        Path v3=temp.resolve("v3.jar");Files.writeString(v3,"version-three");
        InstallTransaction failing=new InstallTransaction(phase->{if(phase==InstallTransaction.Phase.AFTER_MANIFEST)throw new java.io.IOException("injected failure");});
        assertThrows(java.io.IOException.class,()->failing.execute(
                new InstallPlan(instance,v3,managed,List.of(managed),false,"v3")));
        assertEquals("version-two",Files.readString(managed));
        assertArrayEquals(v2Manifest,Files.readAllBytes(manifest));
        assertTrue(transaction.verify(instance.gameDirectory()));
        for(Path file:List.of(config,database,workspace,identity,world,unrelated))assertTrue(Files.exists(file),file.toString());
    }

    @Test void restartRecoveryRollsBackJournalEvenWhenNewManifestWasWritten() throws Exception {
        MinecraftInstance instance=instance();Files.createDirectories(instance.modsDirectory());
        Path managed=instance.modsDirectory().resolve("mcac.jar");
        Path v1=temp.resolve("recover-v1.jar");Files.writeString(v1,"stable");
        InstallTransaction normal=new InstallTransaction();
        normal.execute(new InstallPlan(instance,v1,managed,List.of(),false,"stable"));
        byte[] stableManifest=Files.readAllBytes(instance.gameDirectory().resolve(".mccompanion/install-manifest.json"));
        Path v2=temp.resolve("recover-v2.jar");Files.writeString(v2,"partial");
        InstallTransaction crash=new InstallTransaction(phase->{if(phase==InstallTransaction.Phase.AFTER_MANIFEST)throw new SimulatedCrash();});
        assertThrows(SimulatedCrash.class,()->crash.execute(new InstallPlan(instance,v2,managed,List.of(managed),false,"partial")));
        assertEquals("partial",Files.readString(managed));

        normal.recover(instance.gameDirectory());
        assertEquals("stable",Files.readString(managed));
        assertArrayEquals(stableManifest,Files.readAllBytes(instance.gameDirectory().resolve(".mccompanion/install-manifest.json")));
        assertTrue(normal.verify(instance.gameDirectory()));
        assertFalse(Files.exists(instance.gameDirectory().resolve(".mccompanion/transaction.json")));
    }

    @Test void uninstallOffersPreserveAndExplicitDeleteDataModesWithoutTouchingGameContent() throws Exception {
        MinecraftInstance instance=instance();Files.createDirectories(instance.modsDirectory());
        Path managed=instance.modsDirectory().resolve("mcac.jar");Path artifact=temp.resolve("uninstall.jar");Files.writeString(artifact,"managed");
        InstallPlan plan=new InstallPlan(instance,artifact,managed,List.of(),false,"uninstall-one");
        InstallTransaction transaction=new InstallTransaction();transaction.execute(plan);
        Path runtimeConfig=instance.configDirectory().resolve("minecraft-ai-companion/runtime.json");
        Path userData=instance.gameDirectory().resolve(".mccompanion/user-data/companion.db");
        Path world=instance.gameDirectory().resolve("saves/world/level.dat");
        Path unrelated=instance.modsDirectory().resolve("unrelated.jar");
        for(Path file:List.of(runtimeConfig,userData,world,unrelated)){Files.createDirectories(file.getParent());Files.writeString(file,"keep");}
        transaction.uninstall(instance.gameDirectory());
        assertFalse(Files.exists(managed));
        for(Path file:List.of(runtimeConfig,userData,world,unrelated))assertTrue(Files.exists(file),file.toString());

        transaction.execute(new InstallPlan(instance,artifact,managed,List.of(),false,"uninstall-two"));
        transaction.uninstall(instance.gameDirectory(),InstallTransaction.UninstallMode.DELETE_INSTANCE_USER_DATA);
        assertFalse(Files.exists(instance.gameDirectory().resolve(".mccompanion")));
        assertFalse(Files.exists(instance.configDirectory().resolve("minecraft-ai-companion")));
        assertTrue(Files.exists(world));assertTrue(Files.exists(unrelated));
    }

    private static final class SimulatedCrash extends Error { }
}
