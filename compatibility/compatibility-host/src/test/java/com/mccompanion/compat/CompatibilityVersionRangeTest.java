package com.mccompanion.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompatibilityVersionRangeTest {
    @Test
    void acceptsExactAndBoundedIntervals() {
        assertTrue(CompatibilityResolver.versionMatches("", "1.21.1"));
        assertTrue(CompatibilityResolver.versionMatches("1.21.1", "1.21.1"));
        assertTrue(CompatibilityResolver.versionMatches("[1.20,1.22)", "1.21.1"));
        assertTrue(CompatibilityResolver.versionMatches("(,1.21.1]", "1.21.1"));
        assertTrue(CompatibilityResolver.versionMatches("[1.21.1,)", "1.21.1"));
    }

    @Test
    void rejectsOutsideExclusiveAndUnknownRanges() {
        assertFalse(CompatibilityResolver.versionMatches("[1.20,1.21)", "1.21"));
        assertFalse(CompatibilityResolver.versionMatches("(1.21,1.22)", "1.21"));
        assertFalse(CompatibilityResolver.versionMatches("[1.20,1.22", "1.21"));
        assertFalse(CompatibilityResolver.versionMatches(">=1.20", "1.21"));
    }
}
