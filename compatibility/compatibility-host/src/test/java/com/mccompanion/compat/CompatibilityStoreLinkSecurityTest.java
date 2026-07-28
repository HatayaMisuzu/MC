package com.mccompanion.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityStoreLinkSecurityTest {
    @TempDir Path temporary;

    @Test
    void rejectsLinkedOrJunctionedInternalStoreDirectory() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("store"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path cache = root.resolve("cache");
        createDirectoryLink(cache, outside);

        IOException failure = assertThrows(IOException.class, () -> new CompatibilityStore(root));
        assertTrue(failure.getMessage().contains("REPARSE")
                || failure.getMessage().contains("SYMLINK"));
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            Files.createSymbolicLink(link, target);
            return;
        }
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                link.toString(), target.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.Charset.defaultCharset());
        if (process.waitFor() != 0) {
            throw new IOException("BLOCKED_BY_RUNNER_CAPABILITY: junction creation failed: " + output);
        }
    }
}
