package com.mccompanion.terminal.probe;

import com.mccompanion.terminal.launcher.LoaderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionJsonResolverTest {
    @TempDir Path temp;

    @Test void derivesMinecraftVersionFromCustomForgePackCoordinate() throws Exception {
        Path version = temp.resolve("versions/自定义整合包");
        Files.createDirectories(version);
        Files.writeString(version.resolve("自定义整合包.json"), """
                {"id":"自定义整合包","javaVersion":{"majorVersion":17},
                 "libraries":[{"name":"net.minecraftforge:forge:1.20.1-47.3.22"}]}
                """);
        var resolved = new VersionJsonResolver().resolve(temp, "自定义整合包");
        assertEquals("1.20.1", resolved.minecraftVersion());
        assertEquals(LoaderType.FORGE, resolved.loader());
    }

    @Test void derivesMinecraftVersionFromForgeLaunchArguments() throws Exception {
        Path version = temp.resolve("versions/整合包参数");
        Files.createDirectories(version);
        Files.writeString(version.resolve("整合包参数.json"), """
                {"id":"整合包参数","arguments":{"game":["--fml.mcVersion","1.20.1","--fml.forgeGroup","net.minecraftforge"]},
                 "libraries":[{"name":"net.minecraftforge:fmlloader:47.3.22"}]}
                """);
        var resolved = new VersionJsonResolver().resolve(temp, "整合包参数");
        assertEquals("1.20.1", resolved.minecraftVersion());
        assertEquals(LoaderType.FORGE, resolved.loader());
    }
}
