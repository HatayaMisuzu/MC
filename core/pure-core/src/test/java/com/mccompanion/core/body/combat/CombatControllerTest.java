package com.mccompanion.core.body.combat;

import org.junit.jupiter.api.Test;
import static com.mccompanion.core.body.combat.CombatController.*;
import static org.junit.jupiter.api.Assertions.*;

class CombatControllerTest {
    private static Input input(int tick, Target target, double distance, boolean ready,
                               boolean using, int useTicks, boolean damage) {
        return new Input(tick, target, true, distance, distance <= 3, ready, true, using, useTicks, damage);
    }

    @Test void tracksMovementAndHonorsVanillaCooldownUntilConfirmedDeath() {
        Session session = new Session(Style.MELEE, 1200);
        assertEquals(Action.CHASE, session.tick(input(0, Target.PRESENT, 8, true, false, 0, false)).action());
        assertEquals(Action.ATTACK, session.tick(input(10, Target.PRESENT, 2, true, false, 0, false)).action());
        assertEquals(Action.HOLD, session.tick(input(11, Target.PRESENT, 2, false, false, 0, true)).action());
        assertEquals(Action.CHASE, session.tick(input(20, Target.PRESENT, 7, true, false, 0, false)).action());
        assertEquals(Action.ATTACK, session.tick(input(30, Target.PRESENT, 2, true, false, 0, false)).action());
        assertEquals(Status.COMPLETE, session.tick(input(31, Target.DEAD, 2, false, false, 0, true)).status());
    }

    @Test void shieldMustLowerAndPermitAttackEvenWithPersistentThreat() {
        Session session = new Session(Style.SHIELD, 1200);
        assertEquals(Action.RAISE, session.tick(input(0, Target.PRESENT, 2, true, false, 0, false)).action());
        assertEquals(Action.RAISE, session.tick(input(6, Target.PRESENT, 2, true, true, 6, false)).action());
        assertEquals(Action.LOWER, session.tick(input(12, Target.PRESENT, 2, true, true, 12, false)).action());
        assertEquals(Action.ATTACK, session.tick(input(13, Target.PRESENT, 2, true, false, 0, false)).action());
        assertEquals(Action.RAISE, session.tick(input(22, Target.PRESENT, 2, false, false, 0, true)).action());
    }

    @Test void bowDrawAimReleaseAndRepositionAreLocal() {
        Session session = new Session(Style.BOW, 1200);
        assertEquals(Action.DRAW, session.tick(input(0, Target.PRESENT, 10, true, false, 0, false)).action());
        assertEquals(Action.AIM, session.tick(input(19, Target.PRESENT, 12, true, true, 19, false)).action());
        assertEquals(Action.RELEASE, session.tick(input(20, Target.PRESENT, 13, true, true, 20, false)).action());
        assertEquals(Action.HOLD, session.tick(input(21, Target.PRESENT, 13, true, false, 0, false)).action());
        assertEquals(Action.BACK_OFF, session.tick(input(32, Target.PRESENT, 2, true, false, 0, true)).action());
        assertEquals(Action.CHASE, session.tick(input(33, Target.PRESENT, 20, true, false, 0, false)).action());
    }

    @Test void disappearanceNeverBecomesVerifiedDeathAndDisengageIsTerminal() {
        Session session = new Session(Style.MELEE, 1200);
        assertEquals(Status.RUNNING, session.tick(input(0, Target.MISSING, 0, false, false, 0, false)).status());
        assertEquals("TARGET_LOST_TIMEOUT", session.tick(input(100, Target.MISSING, 0, false, false, 0, false)).code());
        assertEquals(Status.FAILED, session.tick(input(101, Target.PRESENT, 2, true, false, 0, false)).status());
        Session cancelled = new Session(Style.BOW, 1200);
        cancelled.tick(input(0, Target.PRESENT, 10, true, true, 19, false));
        cancelled.disengage();
        assertEquals("COMBAT_DISENGAGED", cancelled.tick(input(1, Target.PRESENT, 10, true, true, 20, false)).code());
    }

    @Test void pauseClearsDrawAndDoesNotSpendTheExecutionBudget() {
        Session session = new Session(Style.BOW, 100);
        session.tick(input(0, Target.PRESENT, 10, true, false, 0, false));
        session.tick(input(19, Target.PRESENT, 10, true, true, 19, false));
        session.pause();
        assertEquals(Action.DRAW, session.tick(input(5000, Target.PRESENT, 10, true, false, 0, false)).action());
        assertEquals("COMBAT_TIMEOUT", session.tick(input(5081, Target.PRESENT, 10, true, false, 0, false)).code());
    }

    @Test void blockedVisibilityDoesNotAttackAndNoDamageCannotSucceed() {
        Session session = new Session(Style.MELEE, 1200);
        assertEquals(Action.CHASE, session.tick(new Input(0, Target.PRESENT, false, 2,
                true, true, false, false, 0, false)).action());
        session.tick(input(1, Target.PRESENT, 2, true, false, 0, false));
        assertEquals("UNCERTAIN_EFFECT", session.tick(input(121, Target.PRESENT, 2, true, false, 0, false)).code());
        assertEquals("TARGET_WORLD_CHANGED", new Session(Style.BOW, 1200)
                .tick(input(0, Target.OTHER_WORLD, 0, false, true, 20, false)).code());
    }
}
