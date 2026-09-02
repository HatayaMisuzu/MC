package com.mccompanion.core.body.combat;

import java.util.Objects;

/** Bounded local execution for one externally selected identity; never selects another target. */
public final class CombatController {
    private CombatController() { }

    public enum Style { MELEE, SHIELD, BOW }
    public enum Target { PRESENT, MISSING, DEAD, OTHER_WORLD }
    public enum Action { HOLD, CHASE, BACK_OFF, ATTACK, RAISE, LOWER, DRAW, AIM, RELEASE }
    public enum Status { RUNNING, COMPLETE, FAILED }
    public record Input(int tick, Target target, boolean visible, double distance,
                        boolean inReach, boolean attackReady, boolean threat,
                        boolean usingItem, int useTicks, boolean damageObserved) { }
    public record Result(Status status, Action action, String code) { }

    public static final class Session {
        private final Style style;
        private final int duration;
        private int activeTicks;
        private int lastTick = -1;
        private int missingSince = -1;
        private int shieldSince = -1;
        private int nextShieldTick;
        private int nextShotTick;
        private int noEffectSince = -1;
        private Result terminal;

        public Session(Style style, int duration) {
            this.style = Objects.requireNonNull(style);
            if (duration < 20 || duration > 2400) throw new IllegalArgumentException("combat duration must be 20..2400");
            this.duration = duration;
        }

        public Style style() { return style; }

        /** A pause cancels held inputs and elapsed-time accounting; it never releases a drawn arrow. */
        public void pause() {
            lastTick = -1;
            missingSince = -1;
            shieldSince = -1;
            noEffectSince = -1;
        }

        public Result disengage() { return finish(Status.FAILED, "COMBAT_DISENGAGED"); }
        public Result fail(String code) { return finish(Status.FAILED, code); }

        public Result tick(Input in) {
            if (terminal != null) return terminal;
            if (lastTick >= 0) activeTicks += Math.max(0, in.tick() - lastTick);
            lastTick = in.tick();
            if (in.target() == Target.OTHER_WORLD) return fail("TARGET_WORLD_CHANGED");
            if (in.target() == Target.DEAD) return finish(Status.COMPLETE, "TARGET_DEAD_CONFIRMED");
            if (activeTicks >= duration) return fail("COMBAT_TIMEOUT");
            if (in.target() == Target.MISSING) {
                if (missingSince < 0) missingSince = in.tick();
                if (in.tick() - missingSince >= 100) return fail("TARGET_LOST_TIMEOUT");
                shieldSince = -1;
                return running(Action.HOLD, "TARGET_TEMPORARILY_LOST");
            }
            missingSince = -1;
            if (in.damageObserved()) noEffectSince = in.tick();
            if (noEffectSince >= 0 && in.tick() - noEffectSince >= 120) return fail("UNCERTAIN_EFFECT");
            if (in.distance() > 48) return fail("TARGET_OUT_OF_RANGE");
            if (!in.visible() || (style == Style.BOW ? in.distance() > 16 : !in.inReach())) {
                shieldSince = -1;
                return running(Action.CHASE, "CHASING_TARGET");
            }
            if (style == Style.BOW) {
                if (in.distance() < 4) return running(Action.BACK_OFF, "BOW_DISTANCE");
                if (in.tick() < nextShotTick) return running(Action.HOLD, "PROJECTILE_IN_FLIGHT");
                if (!in.usingItem()) return running(Action.DRAW, "BOW_DRAW");
                if (in.useTicks() < 20) return running(Action.AIM, "BOW_AIM");
                nextShotTick = in.tick() + 12;
                if (noEffectSince < 0) noEffectSince = in.tick();
                return running(Action.RELEASE, "BOW_RELEASE");
            }
            if (shieldSince >= 0) {
                if (!in.threat() || in.tick() - shieldSince >= 12 || !in.usingItem()) {
                    shieldSince = -1;
                    nextShieldTick = in.tick() + 10;
                    return running(Action.LOWER, "SHIELD_LOWER");
                }
                return running(Action.RAISE, "SHIELD_HOLD");
            }
            if (style == Style.SHIELD && in.threat() && in.tick() >= nextShieldTick) {
                shieldSince = in.tick();
                return running(Action.RAISE, "SHIELD_RAISE");
            }
            if (in.usingItem()) return running(Action.LOWER, "ITEM_USE_END");
            if (!in.attackReady()) return running(Action.HOLD, "ATTACK_COOLDOWN");
            if (noEffectSince < 0) noEffectSince = in.tick();
            return running(Action.ATTACK, "MELEE_ATTACK");
        }

        private static Result running(Action action, String code) {
            return new Result(Status.RUNNING, action, code);
        }

        private Result finish(Status status, String code) {
            terminal = new Result(status, Action.HOLD, code);
            return terminal;
        }
    }
}
