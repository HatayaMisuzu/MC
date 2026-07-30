package com.mccompanion.terminal;

import java.nio.file.Path;
import java.time.Instant;
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

    @Test
    void scanBudgetCountsFilesAndStopsAtTheItemBoundary() {
        var budget = new LauncherRootDiscoveryService.ScanBudget(
                Instant.now().plusSeconds(30), 2);

        assertTrue(budget.tryVisit());
        assertTrue(budget.tryVisit());
        assertTrue(budget.exhausted());
        assertFalse(budget.tryVisit());
    }

    @Test
    void scanBudgetStopsWhenTheDeadlineHasPassed() {
        var budget = new LauncherRootDiscoveryService.ScanBudget(
                Instant.now().minusSeconds(1), 60_000);

        assertTrue(budget.deadlineReached());
        assertTrue(budget.exhausted());
        assertFalse(budget.tryVisit());
    }
}
