package com.mccompanion.protocol.target;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VersionConstraintTest {
    @Test void distinguishesExactVersionsFromSubstringMatches() {
        assertTrue(VersionConstraint.matches("=1.21.1", "1.21.1"));
        assertFalse(VersionConstraint.matches("=1.21.10", "1.21.1"));
        assertFalse(VersionConstraint.matches("=11.21.1", "1.21.1"));
    }
    @Test void evaluatesLoaderIntervalsAndComponentBounds() {
        assertTrue(VersionConstraint.matches("[47.4.10,48)", "47.4.10"));
        assertFalse(VersionConstraint.matches("[47.4.10,48)", "47.4.9"));
        assertFalse(VersionConstraint.matches("[47.4.10,48)", "48.0.0"));
        assertTrue(VersionConstraint.matches("[1.20.1]", "1.20.1"));
        assertTrue(VersionConstraint.matches(">=0.116.13", "0.116.13+1.21.1"));
        assertTrue(VersionConstraint.matches(">=21 <22", "21.0.8"));
        assertFalse(VersionConstraint.matches(">=21 <22", "22"));
        assertTrue(VersionConstraint.matches("=1.20.1 || =1.21.1", "1.21.1"));
    }
    @Test void failsClosedForUnknownOrMalformedConditions() {
        for (String expression : new String[]{"", "unknown", "*", "[21,17)", "[17,21", "=21 || arbitrary", "(,)"}) {
            assertFalse(VersionConstraint.matches(expression, "21"), expression);
        }
        assertFalse(VersionConstraint.matches(">=21", "unknown"));
    }
}
