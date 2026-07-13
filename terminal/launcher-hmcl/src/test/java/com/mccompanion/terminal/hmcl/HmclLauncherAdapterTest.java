package com.mccompanion.terminal.hmcl;

import com.mccompanion.terminal.launcher.DiscoveryContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HmclLauncherAdapterTest {
    @TempDir Path temp;
    @Test void discoversMultipleRelativeRootsAndIgnoresUnknownFields() throws Exception {
        Files.createFile(temp.resolve("HMCL-3.10.3.exe")); Files.createDirectories(temp.resolve(".hmcl"));
        Files.writeString(temp.resolve(".hmcl/hmcl.json"), "{\"unknown\":true,\"configurations\":{"+
                "\"a\":{\"gameDir\":\"game-a\",\"useRelativePath\":true},\"b\":{\"gameDir\":\"game-b\"}}}");
        var found = new HmclLauncherAdapter().discover(new DiscoveryContext(List.of(temp), false));
        assertEquals(1, found.size()); assertEquals(2, found.getFirst().minecraftRoots().size());
        assertTrue(found.getFirst().minecraftRoots().contains(temp.resolve("game-a").toAbsolutePath().normalize()));
    }
    @Test void malformedJsonIsNonFatal() throws Exception {
        Files.createFile(temp.resolve("HMCL.exe")); Files.createDirectories(temp.resolve(".hmcl"));
        Files.writeString(temp.resolve(".hmcl/hmcl.json"), "{");
        assertTrue(new HmclLauncherAdapter().discover(new DiscoveryContext(List.of(temp), false)).isEmpty());
    }
}
