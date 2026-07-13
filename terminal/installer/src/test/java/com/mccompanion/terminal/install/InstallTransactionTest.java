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
}
