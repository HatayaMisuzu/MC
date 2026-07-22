package com.mccompanion.terminal;

import static org.junit.jupiter.api.Assertions.*;

import com.mccompanion.terminal.install.InstallPlan;
import com.mccompanion.terminal.install.InstallTransaction;
import com.mccompanion.terminal.launcher.*;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceUninstallServiceTest {
    @TempDir Path temp;

    @Test void stopsRuntimeAndAppliesExplicitPreserveThenDeletePolicies() throws Exception {
        Path game=temp.resolve("game");Path mods=game.resolve("mods");Files.createDirectories(mods);
        MinecraftInstance instance=new MinecraftInstance("one","launcher-one","Test",temp,temp,game,
                mods,game.resolve("config"),game.resolve("logs"),"1.21.1",LoaderType.FABRIC,"0.16",21,
                Optional.empty(),InstanceIsolation.VERSION_DIRECTORY,DetectionConfidence.HIGH);
        Path control=temp.resolve("control");Path profileDir=control.resolve("profiles/one");Files.createDirectories(profileDir);
        RuntimeProfile profile=new RuntimeProfile("one",profileDir,temp.resolve("runtime.cmd"),8766,18766);
        Path profileDb=profileDir.resolve("companion.db");Files.writeString(profileDb,"memory");
        Path config=game.resolve("config/minecraft-ai-companion/runtime.json");Files.createDirectories(config.getParent());Files.writeString(config,"config");
        Path world=game.resolve("saves/world/level.dat");Files.createDirectories(world.getParent());Files.writeString(world,"world");
        Path other=mods.resolve("other.jar");Files.writeString(other,"other");
        Path artifact=temp.resolve("mcac.jar");Files.writeString(artifact,"managed");Path managed=mods.resolve("mcac.jar");
        InstallTransaction transaction=new InstallTransaction();
        transaction.execute(new InstallPlan(instance,artifact,managed,List.of(),false,"preserve"));
        AtomicInteger stops=new AtomicInteger();
        InstanceUninstallService service=new InstanceUninstallService(ignored->stops.incrementAndGet(),transaction);

        service.uninstall(instance,profile,control,InstanceUninstallService.DataPolicy.PRESERVE);
        assertEquals(1,stops.get());assertFalse(Files.exists(managed));
        assertTrue(Files.exists(profileDb));assertTrue(Files.exists(config));

        transaction.execute(new InstallPlan(instance,artifact,managed,List.of(),false,"delete"));
        service.uninstall(instance,profile,control,InstanceUninstallService.DataPolicy.DELETE);
        assertEquals(2,stops.get());assertFalse(Files.exists(profileDir));assertFalse(Files.exists(config));
        assertFalse(Files.exists(game.resolve(".mccompanion")));
        assertTrue(Files.exists(world));assertTrue(Files.exists(other));
    }
}
