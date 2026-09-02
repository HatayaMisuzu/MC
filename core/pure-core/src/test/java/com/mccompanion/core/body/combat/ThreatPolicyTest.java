package com.mccompanion.core.body.combat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.mccompanion.core.body.combat.ThreatPolicy.*;

class ThreatPolicyTest {
    private final UUID target = new UUID(0, 1);
    private Threat threat(long id, Kind kind, double distance, boolean attacking, boolean owner, boolean primed) {
        return new Threat(new UUID(0, id), kind, distance, attacking, owner, primed);
    }
    @Test void lowHealthOverridesOwnerDefenseAndUsesHysteresis() {
        var owner = threat(2, Kind.MELEE, 2, true, true, false);
        assertEquals("LOW_HEALTH", decide(6, 20, target, List.of(owner)).reason());
        assertFalse(recovered(10, 20));
        assertTrue(recovered(12, 20));
        assertEquals(Action.RETREAT, decide(5, 20, null, List.of()).action());
    }
    @Test void explosiveThreatPreemptsSelectedTargetAndOwnerDefenseRegardlessOfInputOrder() {
        var zombie = threat(1, Kind.MELEE, 2, true, true, false);
        var creeper = threat(2, Kind.CREEPER, 4, true, false, true);
        assertEquals(creeper, decide(20, 20, target, List.of(zombie, creeper)).target());
        assertEquals(creeper, decide(20, 20, target, List.of(creeper, zombie)).target());
    }
    @Test void multipleEnemiesAreNotHiddenByExplicitTargetExemption() {
        var zombie = threat(1, Kind.MELEE, 2, true, false, false);
        assertEquals(Action.CONTINUE, decide(20, 20, target, List.of(zombie)).action());
        assertEquals("MULTIPLE_HOSTILES", decide(20, 20, target,
                List.of(zombie, threat(2, Kind.MELEE, 3, true, false, false))).reason());
    }
    @Test void rangedIntentAndOwnerDamageHaveBoundedDistinctReactions() {
        var ranged = threat(2, Kind.RANGED, 12, true, false, false);
        assertEquals("RANGED_THREAT", decide(20, 20, target, List.of(ranged)).reason());
        assertEquals(16, ranged.safeDistance());
        assertEquals(Action.CONTINUE, decide(20, 20, ranged.id(), List.of(ranged)).action());
        assertEquals(Action.DEFEND_OWNER, decide(20, 20, target,
                List.of(threat(3, Kind.MELEE, 6, true, true, false))).action());
        assertEquals(Action.CONTINUE, decide(20, 20, target,
                List.of(threat(3, Kind.MELEE, 6, false, false, false))).action());
    }
}
