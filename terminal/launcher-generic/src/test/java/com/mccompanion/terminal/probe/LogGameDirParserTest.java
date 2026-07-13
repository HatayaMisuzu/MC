package com.mccompanion.terminal.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogGameDirParserTest {
    @TempDir Path temp;

    @Test void acceptsCustomUnicodeGameDirWhoseNameDiffersFromVersion() throws Exception {
        Path root = temp.resolve("Minecraft 根目录").toAbsolutePath();
        Path game = temp.resolve("我的独立世界").toAbsolutePath();
        Path log = temp.resolve("launcher.log");
        Files.writeString(log, "Launching --version fabric-1.21.1 --assetsDir \"" + root.resolve("assets")
                + "\" --gameDir \"" + game + "\"\n");
        assertEquals(game.normalize(), new LogGameDirParser()
                .latest(List.of(log), "fabric-1.21.1", root).orElseThrow());
    }

    @Test void rejectsWrongVersionAndExpiredLogs() throws Exception {
        Path root = temp.resolve(".minecraft").toAbsolutePath();
        Path log = temp.resolve("launcher.log");
        Files.writeString(log, "--version other --assetsDir \"" + root.resolve("assets")
                + "\" --gameDir \"" + temp.resolve("custom") + "\"\n");
        assertTrue(new LogGameDirParser().latest(List.of(log), "wanted", root).isEmpty());
        Files.writeString(log, "--version wanted --assetsDir \"" + root.resolve("assets")
                + "\" --gameDir \"" + temp.resolve("custom") + "\"\n");
        Files.setLastModifiedTime(log, FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS)));
        assertTrue(new LogGameDirParser().latest(List.of(log), "wanted", root).isEmpty());
    }
}
