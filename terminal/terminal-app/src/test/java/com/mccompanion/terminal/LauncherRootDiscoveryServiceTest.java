package com.mccompanion.terminal;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherRootDiscoveryServiceTest {
    @Test
    void automaticDiscoverySkipsBuildAndPlaywrightFixtures() {
        assertTrue(LauncherRootDiscoveryService.isSkippedDirectory(Path.of("build")));
        assertTrue(LauncherRootDiscoveryService.isSkippedDirectory(Path.of("mcac-playwright-fixture")));
        assertTrue(LauncherRootDiscoveryService.isSkippedDirectory(Path.of("PLAYWRIGHT-FIXTURE")));
        assertFalse(LauncherRootDiscoveryService.isSkippedDirectory(Path.of("My Minecraft")));
    }
}
