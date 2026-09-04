package com.mccompanion.core.body.interaction;

import java.util.Objects;

/**
 * Loader-neutral continuous controller for externally selected entity targets.
 *
 * <p>The controller chooses only a bounded local action. Minecraft adapters remain responsible for
 * target resolution, path planning, collision-aware movement and observed terminal effects.
 */
public final class EntityInteractionController {
    private EntityInteractionController() { }

    public enum Behavior { FOLLOW, APPROACH, KEEP_DISTANCE, CHASE, ESCORT, FLEE, FACE }

    public enum TargetState { PRESENT, TEMPORARILY_MISSING, DEAD, OTHER_WORLD }

    public enum Action { MOVE_TOWARD, MOVE_AWAY, HOLD, FACE }

    public enum Status { RUNNING, COMPLETE, FAILED }

    public record Position(double x, double y, double z) {
        public Position {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("entity interaction position must be finite");
            }
        }

        public double distanceSquared(Position other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    public record Config(double minimumDistance, double maximumDistance, int lostTimeoutTicks) {
        public Config {
            if (!Double.isFinite(minimumDistance) || !Double.isFinite(maximumDistance)
                    || minimumDistance < 0.0D || maximumDistance < minimumDistance
                    || maximumDistance > 64.0D || lostTimeoutTicks < 1 || lostTimeoutTicks > 1200) {
                throw new IllegalArgumentException("invalid entity interaction configuration");
            }
        }

        public static Config defaults(Behavior behavior) {
            return switch (Objects.requireNonNull(behavior, "behavior")) {
                case FOLLOW -> new Config(0.0D, 3.0D, 100);
                case APPROACH -> new Config(0.0D, 2.0D, 100);
                case KEEP_DISTANCE -> new Config(3.0D, 6.0D, 100);
                case CHASE -> new Config(0.0D, 1.5D, 100);
                case ESCORT -> new Config(0.0D, 4.0D, 100);
                case FLEE -> new Config(8.0D, 12.0D, 100);
                case FACE -> new Config(0.0D, 64.0D, 100);
            };
        }
    }

    public record Input(int tick, TargetState targetState, Position bodyPosition,
                        Position targetPosition, boolean visible) {
        public Input {
            if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
            Objects.requireNonNull(targetState, "targetState");
            Objects.requireNonNull(bodyPosition, "bodyPosition");
            if (targetState == TargetState.PRESENT) Objects.requireNonNull(targetPosition, "targetPosition");
        }
    }

    public record Result(Status status, Action action, String code, double distanceSquared) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(action, "action");
            code = code == null ? "" : code;
            if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0D) {
                throw new IllegalArgumentException("distanceSquared must be finite and non-negative");
            }
        }
    }

    public static final class Session {
        private final Behavior behavior;
        private final Config config;
        private Integer missingSinceTick;

        public Session(Behavior behavior, Config config) {
            this.behavior = Objects.requireNonNull(behavior, "behavior");
            this.config = Objects.requireNonNull(config, "config");
        }

        public Behavior behavior() { return behavior; }

        public Config config() { return config; }

        public Result tick(Input input) {
            Objects.requireNonNull(input, "input");
            if (input.targetState() == TargetState.DEAD) {
                return result(Status.FAILED, Action.HOLD, "TARGET_DEAD", 0.0D);
            }
            if (input.targetState() == TargetState.OTHER_WORLD) {
                return result(Status.FAILED, Action.HOLD, "TARGET_WORLD_CHANGED", 0.0D);
            }
            if (input.targetState() == TargetState.TEMPORARILY_MISSING) {
                if (missingSinceTick == null) missingSinceTick = input.tick();
                if (input.tick() - missingSinceTick >= config.lostTimeoutTicks()) {
                    return result(Status.FAILED, Action.HOLD, "TARGET_LOST_TIMEOUT", 0.0D);
                }
                return result(Status.RUNNING, Action.HOLD, "TARGET_TEMPORARILY_LOST", 0.0D);
            }

            boolean reappeared = missingSinceTick != null;
            missingSinceTick = null;
            double distanceSquared = input.bodyPosition().distanceSquared(input.targetPosition());
            double minimumSquared = config.minimumDistance() * config.minimumDistance();
            double maximumSquared = config.maximumDistance() * config.maximumDistance();
            Action action;
            Status status = Status.RUNNING;
            String code;
            switch (behavior) {
                case FOLLOW, ESCORT -> {
                    action = distanceSquared > maximumSquared ? Action.MOVE_TOWARD : Action.HOLD;
                    code = action == Action.HOLD ? "DISTANCE_MAINTAINED" : "CLOSING_DISTANCE";
                }
                case APPROACH -> {
                    action = distanceSquared > maximumSquared ? Action.MOVE_TOWARD : Action.HOLD;
                    status = action == Action.HOLD ? Status.COMPLETE : Status.RUNNING;
                    code = status == Status.COMPLETE ? "APPROACHED" : "CLOSING_DISTANCE";
                }
                case KEEP_DISTANCE -> {
                    if (distanceSquared < minimumSquared) action = Action.MOVE_AWAY;
                    else if (distanceSquared > maximumSquared) action = Action.MOVE_TOWARD;
                    else action = Action.HOLD;
                    code = action == Action.HOLD ? "DISTANCE_MAINTAINED"
                            : action == Action.MOVE_AWAY ? "INCREASING_DISTANCE" : "CLOSING_DISTANCE";
                }
                case CHASE -> {
                    action = distanceSquared > maximumSquared ? Action.MOVE_TOWARD : Action.HOLD;
                    code = action == Action.HOLD ? "CHASE_RANGE_MAINTAINED" : "CHASING";
                }
                case FLEE -> {
                    action = distanceSquared < minimumSquared ? Action.MOVE_AWAY : Action.HOLD;
                    code = action == Action.HOLD ? "SAFE_DISTANCE_MAINTAINED" : "FLEEING";
                }
                case FACE -> {
                    action = Action.FACE;
                    code = input.visible() ? "FACING_TARGET" : "FACING_LAST_OBSERVED_TARGET";
                }
                default -> throw new IllegalStateException("Unsupported behavior " + behavior);
            }
            if (reappeared && status == Status.RUNNING) code = "TARGET_REAPPEARED";
            return result(status, action, code, distanceSquared);
        }

        private static Result result(Status status, Action action, String code, double distanceSquared) {
            return new Result(status, action, code, distanceSquared);
        }
    }
}
