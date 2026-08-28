package com.mccompanion.core.body.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class EntityInteractionControllerTest {
    private static final EntityInteractionController.Position ORIGIN = position(0, 0, 0);

    @Test
    void allSevenBehaviorsProduceTheirBoundedLocalActions() {
        assertAction(EntityInteractionController.Behavior.FOLLOW, position(8, 0, 0),
                EntityInteractionController.Action.MOVE_TOWARD);
        assertAction(EntityInteractionController.Behavior.APPROACH, position(8, 0, 0),
                EntityInteractionController.Action.MOVE_TOWARD);
        assertAction(EntityInteractionController.Behavior.KEEP_DISTANCE, position(1, 0, 0),
                EntityInteractionController.Action.MOVE_AWAY);
        assertAction(EntityInteractionController.Behavior.KEEP_DISTANCE, position(10, 0, 0),
                EntityInteractionController.Action.MOVE_TOWARD);
        assertAction(EntityInteractionController.Behavior.CHASE, position(8, 0, 0),
                EntityInteractionController.Action.MOVE_TOWARD);
        assertAction(EntityInteractionController.Behavior.ESCORT, position(8, 0, 0),
                EntityInteractionController.Action.MOVE_TOWARD);
        assertAction(EntityInteractionController.Behavior.FLEE, position(2, 0, 0),
                EntityInteractionController.Action.MOVE_AWAY);
        assertAction(EntityInteractionController.Behavior.FACE, position(8, 0, 0),
                EntityInteractionController.Action.FACE);
    }

    @Test
    void targetMovementChangesActionWithoutAHighLevelRestart() {
        var session = session(EntityInteractionController.Behavior.FOLLOW, 100);
        assertEquals(EntityInteractionController.Action.HOLD, tick(session, 1, position(2, 0, 0)).action());
        assertEquals(EntityInteractionController.Action.MOVE_TOWARD,
                tick(session, 2, position(-9, 0, 0)).action());
        assertEquals(EntityInteractionController.Action.HOLD, tick(session, 3, position(1, 0, 0)).action());
    }

    @Test
    void temporaryLossReappearsButLongLossFailsDeterministically() {
        var session = session(EntityInteractionController.Behavior.CHASE, 4);
        assertEquals("TARGET_TEMPORARILY_LOST", missing(session, 10).code());
        assertEquals("TARGET_REAPPEARED", tick(session, 12, position(7, 0, 0)).code());
        assertEquals("TARGET_TEMPORARILY_LOST", missing(session, 20).code());
        assertEquals(EntityInteractionController.Status.FAILED, missing(session, 24).status());
        assertEquals("TARGET_LOST_TIMEOUT", missing(session, 24).code());
    }

    @Test
    void deathAndDimensionChangeAreTerminalAndVerifiedPlayerIdentityCannotBindAMob() {
        var session = session(EntityInteractionController.Behavior.ESCORT, 100);
        assertEquals("TARGET_DEAD", state(session, 1, EntityInteractionController.TargetState.DEAD).code());
        assertEquals("TARGET_WORLD_CHANGED",
                state(session, 2, EntityInteractionController.TargetState.OTHER_WORLD).code());
        assertThrows(IllegalArgumentException.class, () -> new EntityTargetIdentity(
                UUID.randomUUID(), EntityTargetIdentity.Source.VERIFIED_PLAYER, "mob", false));
    }

    private static void assertAction(EntityInteractionController.Behavior behavior,
                                     EntityInteractionController.Position target,
                                     EntityInteractionController.Action expected) {
        assertEquals(expected, tick(session(behavior, 100), 1, target).action());
    }

    private static EntityInteractionController.Session session(
            EntityInteractionController.Behavior behavior, int lostTimeout) {
        var defaults = EntityInteractionController.Config.defaults(behavior);
        return new EntityInteractionController.Session(behavior, new EntityInteractionController.Config(
                defaults.minimumDistance(), defaults.maximumDistance(), lostTimeout));
    }

    private static EntityInteractionController.Result tick(EntityInteractionController.Session session,
                                                            int tick,
                                                            EntityInteractionController.Position target) {
        return session.tick(new EntityInteractionController.Input(tick,
                EntityInteractionController.TargetState.PRESENT, ORIGIN, target, true));
    }

    private static EntityInteractionController.Result missing(EntityInteractionController.Session session, int tick) {
        return state(session, tick, EntityInteractionController.TargetState.TEMPORARILY_MISSING);
    }

    private static EntityInteractionController.Result state(EntityInteractionController.Session session, int tick,
                                                             EntityInteractionController.TargetState state) {
        return session.tick(new EntityInteractionController.Input(tick, state, ORIGIN, null, false));
    }

    private static EntityInteractionController.Position position(double x, double y, double z) {
        return new EntityInteractionController.Position(x, y, z);
    }
}
