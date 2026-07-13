package com.mccompanion.terminal;

import com.mccompanion.terminal.launcher.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class SupportBundleServiceTest {
    @TempDir Path temp;

    @Test void removesSecretsAddressesPathsAndIdentifiers() throws Exception {
        Path game=temp.resolve("game");Files.createDirectories(game.resolve("logs"));Files.createDirectories(game.resolve("mods"));
        String sensitive="token=abc123 Authorization: Bearer hidden API_KEY=qwerty C:\\Users\\Alice\\game "
                +"127.0.0.1:25565 2001:db8::1 123e4567-e89b-42d3-a456-426614174000 server.example.com";
        Files.writeString(game.resolve("logs/latest.log"),sensitive,StandardCharsets.UTF_8);
        Files.writeString(game.resolve("mods/minecraft-ai-companion-1.21.1-0.3.0.jar"), "fixture");
        MinecraftInstance instance=new MinecraftInstance("id","launcher","Test",temp,temp,game,game.resolve("mods"),
                game.resolve("config"),game.resolve("logs"),"1.21.1",LoaderType.FABRIC,"1",21, Optional.empty(),
                InstanceIsolation.EXPLICIT,DetectionConfidence.HIGH);
        Path output=temp.resolve("support.zip");new SupportBundleService().collect(instance,output);
        try(ZipFile zip=new ZipFile(output.toFile())){
            String log=new String(zip.getInputStream(zip.getEntry("minecraft-latest.log")).readAllBytes(),StandardCharsets.UTF_8);
            assertFalse(log.contains("abc123"));assertFalse(log.contains("hidden"));assertFalse(log.contains("qwerty"));
            assertFalse(log.contains("Alice"));assertFalse(log.contains("127.0.0.1"));assertFalse(log.contains("2001:db8"));
            assertFalse(log.contains("123e4567"));assertFalse(log.contains("server.example.com"));
            String mods=new String(zip.getInputStream(zip.getEntry("mods.txt")).readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(mods.contains("minecraft-ai-companion-1.21.1-0.3.0.jar"));
        }
    }
}
