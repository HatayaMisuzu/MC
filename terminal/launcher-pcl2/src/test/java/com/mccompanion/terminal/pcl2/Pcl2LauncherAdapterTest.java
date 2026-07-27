package com.mccompanion.terminal.pcl2;

import com.mccompanion.terminal.launcher.DiscoveryContext;
import com.mccompanion.terminal.launcher.InstanceIsolation;
import com.mccompanion.terminal.launcher.LoaderType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Pcl2LauncherAdapterTest {
    @TempDir Path temp;
    @Test void resolvesDollarRootUnicodeAndVersionIsolation() throws Exception {
        Files.createFile(temp.resolve("Plain Craft Launcher 2.exe"));
        Files.createDirectories(temp.resolve("PCL"));
        Files.writeString(temp.resolve("PCL/Setup.ini"), "LaunchFolderSelect:$.minecraft\\\n");
        Path version = temp.resolve(".minecraft/versions/测试整合包");
        Files.createDirectories(version.resolve("PCL"));
        Files.writeString(version.resolve("PCL/Setup.ini"), "VersionArgumentIndieV2:True\n");
        Files.writeString(version.resolve("测试整合包.json"), "{\"id\":\"1.21.1\",\"javaVersion\":{\"majorVersion\":21},\"libraries\":[{\"name\":\"net.fabricmc:fabric-loader:0.16.14\"}]}");
        var adapter = new Pcl2LauncherAdapter();
        var launchers = adapter.discover(new DiscoveryContext(List.of(temp), false));
        assertEquals(1, launchers.size());
        var instances = adapter.discoverInstances(launchers.getFirst());
        assertEquals(1, instances.size());
        assertEquals(version.toAbsolutePath().normalize(), instances.getFirst().gameDirectory());
        assertEquals(InstanceIsolation.VERSION_DIRECTORY, instances.getFirst().isolation());
    }
    @Test void malformedSetupDoesNotCrashGlobalScan() throws Exception {
        Files.createFile(temp.resolve("PCL.exe")); Files.createDirectories(temp.resolve("PCL"));
        Files.writeString(temp.resolve("PCL/Setup.ini"), "LaunchFolderSelect:$../../escape\n");
        assertTrue(new Pcl2LauncherAdapter().discover(new DiscoveryContext(List.of(temp), false)).isEmpty());
    }

    @Test void discoversIsolatedForge1201WithJava17() throws Exception {
        Files.createFile(temp.resolve("PCL2.exe"));
        Files.createDirectories(temp.resolve("PCL"));
        Files.writeString(temp.resolve("PCL/Setup.ini"), "LaunchFolderSelect:$.minecraft\\\n");
        Path version = temp.resolve(".minecraft/versions/Forge验收");
        Files.createDirectories(version.resolve("PCL"));
        Files.writeString(version.resolve("PCL/Setup.ini"), "VersionArgumentIndieV2:True\n");
        Files.writeString(version.resolve("Forge验收.json"), """
                {"id":"Forge验收","javaVersion":{"majorVersion":17},
                 "libraries":[{"name":"net.minecraftforge:forge:1.20.1-47.4.10"}]}
                """);

        var adapter = new Pcl2LauncherAdapter();
        var instance = adapter.discoverInstances(
                adapter.discover(new DiscoveryContext(List.of(temp), false)).getFirst()).getFirst();
        assertEquals(LoaderType.FORGE, instance.loader());
        assertEquals("1.20.1", instance.minecraftVersion());
        assertEquals(17, instance.requiredJavaMajor());
        assertEquals(version.toAbsolutePath().normalize(), instance.gameDirectory());
    }
}
