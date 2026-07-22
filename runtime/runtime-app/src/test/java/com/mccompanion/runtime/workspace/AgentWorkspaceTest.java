package com.mccompanion.runtime.workspace;

import com.mccompanion.runtime.security.Digests;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentWorkspaceTest {
    @TempDir Path temporary;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void storesVersionedHashedResourcesWithoutCrossScopeVisibility() throws Exception {
        AgentWorkspace profileA = workspace("profile-a");
        WorkspaceResource first = profileA.save("companion-a", "skills/gather/draft.yaml", "version: one");
        assertEquals(1, first.version());
        assertEquals(Digests.sha256("version: one"), first.sha256());
        assertEquals("version: one",
                profileA.read("companion-a", "skills/gather/draft.yaml").content());

        WorkspaceResource unchanged = profileA.save("companion-a", "skills/gather/draft.yaml", "version: one");
        assertEquals(1, unchanged.version());
        WorkspaceResource second = profileA.save("companion-a", "skills/gather/draft.yaml", "version: two");
        assertEquals(2, second.version());
        assertEquals(1, profileA.list("companion-a", "skills/").size());
        assertTrue(profileA.list("companion-b", "skills/").isEmpty());
        assertTrue(workspace("profile-b").list("companion-a", "skills/").isEmpty());
        assertFalse(first.logicalPath().contains(temporary.toString()));
    }

    @Test
    void rejectsTraversalAbsoluteAdsUnicodeReservedAndExecutablePaths() {
        AgentWorkspace workspace = workspace("profile");
        for (String path : java.util.List.of(
                "../escape.yaml", "skills/../escape.yaml", "/absolute/file.yaml",
                "C:/absolute/file.yaml", "skills\\bad\\draft.yaml", "skills/name/draft.yaml:secret",
                "skills/con/draft.yaml", "skills/name/payload.exe")) {
            assertThrows(IllegalArgumentException.class, () -> workspace.save("c1", path, "safe"));
        }
        String decomposed = Normalizer.normalize("skills/caf\u00e9/draft.yaml", Normalizer.Form.NFD);
        assertThrows(IllegalArgumentException.class, () -> workspace.save("c1", decomposed, "safe"));
    }

    @Test
    void enforcesPerFileFileCountAndTotalByteQuotas() throws Exception {
        AgentWorkspace workspace = new AgentWorkspace(temporary.resolve("quota"), "profile",
                new AgentWorkspace.Quota(2, 12, 8), CLOCK);
        workspace.save("c1", "skills/a/a.yaml", "123456");
        workspace.save("c1", "skills/b/b.json", "123456");
        assertThrows(IllegalArgumentException.class,
                () -> workspace.save("c1", "skills/c/c.md", "1"));
        assertThrows(IllegalArgumentException.class,
                () -> workspace.save("c2", "skills/a/a.yaml", "123456789"));
        assertThrows(IllegalArgumentException.class,
                () -> workspace.save("c1", "skills/a/a.yaml", "1234567"));
    }

    @Test
    void restoresRetainedContentAsNewVersionAndPrunesOldBackupsPerScope() throws Exception {
        AgentWorkspace workspace = workspace("profile");
        String path = "skills/restore/draft.yaml";
        for (int version = 1; version <= 11; version++) {
            workspace.save("c1", path, "version: " + version);
        }

        Path backupDirectory = temporary.resolve("workspace")
                .resolve(Digests.sha256("profile").substring(0, 32))
                .resolve(Digests.sha256("c1").substring(0, 32))
                .resolve(".mcac-backups").resolve(Digests.sha256(path));
        try (var backups = Files.list(backupDirectory)) {
            assertEquals(AgentWorkspace.BACKUP_RETENTION,
                    backups.filter(file -> file.getFileName().toString().endsWith(".bak")).count());
        }
        List<WorkspaceRetainedVersion> retained = workspace.retainedVersions("c1", path);
        assertEquals(List.of(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L),
                retained.stream().map(WorkspaceRetainedVersion::version).toList());
        assertEquals(Digests.sha256("version: 10"), retained.get(0).sha256());
        assertThrows(IllegalArgumentException.class, () -> workspace.restore("c1", path, 2),
                "a version outside the retention window must not be fabricated");

        WorkspaceResource restored = workspace.restore("c1", path, 3);
        assertEquals(12, restored.version(), "restore must create a new monotonic audit version");
        assertEquals("version: 3", workspace.read("c1", path).content());
        assertEquals(Digests.sha256("version: 3"), restored.sha256());
        try (var backups = Files.list(backupDirectory)) {
            assertEquals(AgentWorkspace.BACKUP_RETENTION,
                    backups.filter(file -> file.getFileName().toString().endsWith(".bak")).count());
        }

        Files.writeString(backupDirectory.resolve("4.bak"), "tampered");
        IOException tampered = assertThrows(IOException.class, () -> workspace.restore("c1", path, 4));
        assertTrue(tampered.getMessage().contains("integrity"));
        assertThrows(IllegalArgumentException.class, () -> workspace.restore("c2", path, 3));
        assertThrows(IllegalArgumentException.class, () -> workspace.restore("c1", path, 99));
        assertEquals(12, workspace.restore("c1", path, 12).version());
    }

    @Test
    void rejectsSymbolicLinkEscapeWhenPlatformCanCreateLinks() throws Exception {
        Path root = temporary.resolve("links");
        AgentWorkspace workspace = new AgentWorkspace(root, "profile",
                AgentWorkspace.DEFAULT_QUOTA, CLOCK);
        workspace.list("c1", "");
        Path resources = root.resolve(Digests.sha256("profile").substring(0, 32))
                .resolve(Digests.sha256("c1").substring(0, 32)).resolve("resources");
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path link = resources.resolve("skills");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.abort("symbolic links are unavailable on this runner");
        }
        IOException failure = assertThrows(IOException.class,
                () -> workspace.save("c1", "skills/escape/draft.yaml", "safe"));
        assertTrue(failure.getMessage().contains("links") || failure.getMessage().contains("scope"));
        assertTrue(Files.list(outside).findAny().isEmpty());
    }

    private AgentWorkspace workspace(String profile) {
        return new AgentWorkspace(temporary.resolve("workspace"), profile,
                AgentWorkspace.DEFAULT_QUOTA, CLOCK);
    }
}
