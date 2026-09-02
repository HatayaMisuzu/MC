package com.mccompanion.core.body.build;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic, one-world-action-per-tick executor for {@link SmallBlueprint}. */
public final class SmallBlueprintExecutor {
    public enum Status { RUNNING, COMPLETE, BLOCKED }
    public enum Preparation { READY, WAIT, BLOCKED }

    public record Position(int x, int y, int z) {
        public Position offset(Direction direction) {
            return new Position(x + direction.x, y + direction.y, z + direction.z);
        }
    }

    /** Direction from a clicked support block toward the block being placed. */
    public enum Direction {
        UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0), DOWN(0, -1, 0);
        final int x, y, z;
        Direction(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public Direction opposite() {
            return switch (this) {
                case UP -> DOWN; case DOWN -> UP; case NORTH -> SOUTH;
                case SOUTH -> NORTH; case WEST -> EAST; case EAST -> WEST;
            };
        }
    }

    public record WorldBlock(String id, Map<String, String> state, boolean replaceable, boolean loaded) {
        public WorldBlock {
            id = id == null ? "" : id;
            state = Map.copyOf(state == null ? Map.of() : state);
        }
    }

    public record Result(Status status, String code, int completed, int total,
                         int temporarySupports, Position activePosition) {
        public static Result blocked(String code, Session session, Position position) {
            return new Result(Status.BLOCKED, code, session.completed.size(), session.plan.blocks().size(),
                    session.temporarySupports.size(), position);
        }
    }

    public interface Environment {
        WorldBlock block(Position position);
        boolean validMaterial(String blockId, Map<String, String> requestedState);
        int inventoryCount(String blockId);
        boolean playerOccupies(Position position);
        default boolean actorOccupies(Position position) { return false; }
        default Preparation vacate(Position position) { return Preparation.BLOCKED; }
        Preparation prepare(Position target, Position interactionBlock);
        String preparationFailure();
        /** Returns null only after the exact live block/state postcondition was observed. */
        String place(Position target, Position support, Direction face, String blockId,
                     Map<String, String> requestedState);
        /** Returns null only after the temporary block was observed absent. */
        String remove(Position position, String blockId);
    }

    public static final class Session {
        private final SmallBlueprint plan;
        private final LinkedHashSet<Integer> completed;
        private final LinkedHashMap<Integer, String> selections;
        private final LinkedHashMap<Position, String> temporarySupports;
        private int supportsPlaced;
        private boolean reconciled;
        private int ticksWithoutProgress;

        public Session(SmallBlueprint plan) {
            this(plan, Set.of(), Map.of(), Map.of());
        }

        public Session(SmallBlueprint plan, Set<Integer> completed, Map<Integer, String> selections,
                       Map<Position, String> temporarySupports) {
            this(plan, completed, selections, temporarySupports,
                    temporarySupports == null ? 0 : temporarySupports.size());
        }

        public Session(SmallBlueprint plan, Set<Integer> completed, Map<Integer, String> selections,
                       Map<Position, String> temporarySupports, int supportsPlaced) {
            this.plan = Objects.requireNonNull(plan, "plan");
            this.completed = new LinkedHashSet<>(completed == null ? Set.of() : completed);
            this.selections = new LinkedHashMap<>(selections == null ? Map.of() : selections);
            this.temporarySupports = new LinkedHashMap<>(temporarySupports == null ? Map.of() : temporarySupports);
            if (this.completed.stream().anyMatch(index -> index < 0 || index >= plan.blocks().size())) {
                throw new IllegalArgumentException("completed blueprint index is outside the plan");
            }
            if (this.selections.entrySet().stream().anyMatch(entry -> entry.getKey() < 0
                    || entry.getKey() >= plan.blocks().size()
                    || !plan.blocks().get(entry.getKey()).materials().contains(entry.getValue()))) {
                throw new IllegalArgumentException("selected material is outside the blueprint");
            }
            if (supportsPlaced < this.temporarySupports.size() || supportsPlaced > plan.temporarySupport().maxBlocks()
                    || this.temporarySupports.entrySet().stream().anyMatch(entry ->
                    !plan.temporarySupport().blocks().contains(entry.getValue())
                            || !blueprintContains(plan, entry.getKey().offset(Direction.UP)))) {
                throw new IllegalArgumentException("temporary support state is outside the blueprint authority");
            }
            this.supportsPlaced = supportsPlaced;
        }

        public SmallBlueprint plan() { return plan; }
        public Set<Integer> completed() { return Set.copyOf(completed); }
        public Map<Integer, String> selections() { return Map.copyOf(selections); }
        public Map<Position, String> temporarySupports() { return Map.copyOf(temporarySupports); }
        public int supportsPlaced() { return supportsPlaced; }
        public void requireReconciliation() { reconciled = false; ticksWithoutProgress = 0; }
    }

    public Result tick(Session session, Environment environment) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(environment, "environment");
        if (++session.ticksWithoutProgress > 1200) {
            return Result.blocked("BLUEPRINT_PROGRESS_TIMEOUT", session, null);
        }
        if (!session.reconciled) {
            reconcile(session, environment);
            String selectionFailure = selectMaterials(session, environment);
            if (selectionFailure != null) return Result.blocked(selectionFailure, session, null);
            session.reconciled = true;
        }

        var completedSupport = session.temporarySupports.entrySet().stream()
                .filter(entry -> supportsCompletedBlock(session, entry.getKey()))
                .findFirst().orElse(null);
        if (completedSupport != null && session.plan.temporarySupport().cleanup()) {
            return cleanupSupport(session, environment, completedSupport);
        }

        for (SmallBlueprint.IndexedBlock indexed : session.plan.buildOrder()) {
            int index = indexed.index();
            SmallBlueprint.Block block = indexed.block();
            Position target = absolute(session.plan.anchor(), block.offset());
            String selected = session.selections.get(index);
            WorldBlock current = environment.block(target);
            if (!current.loaded()) return Result.blocked("TARGET_CHUNK_UNLOADED", session, target);
            if (matches(current, block, selected)) {
                session.completed.add(index);
                continue;
            }
            session.completed.remove(index);
            if (environment.playerOccupies(target)) {
                if (!environment.actorOccupies(target)) return Result.blocked("PLAYER_OCCUPIED", session, target);
                Preparation vacated = environment.vacate(target);
                if (vacated == Preparation.BLOCKED) {
                    return Result.blocked(nonblank(environment.preparationFailure(), "PLAYER_OCCUPIED"), session, target);
                }
                return running(session, target);
            }
            if (!current.replaceable()) return Result.blocked("PLACEMENT_TARGET_OCCUPIED", session, target);
            if (environment.inventoryCount(selected) < 1) {
                session.reconciled = false;
                return Result.blocked("MATERIALS_INSUFFICIENT", session, target);
            }

            Support support = findSupport(target, environment);
            if (support == null) {
                Result temporary = placeTemporarySupport(session, environment, target);
                if (temporary != null) return temporary;
                support = findSupport(target, environment);
                if (support == null) return Result.blocked("PLACEMENT_SUPPORT_MISSING", session, target);
            }
            Preparation prepared = environment.prepare(target, support.position);
            if (prepared == Preparation.WAIT) return running(session, target);
            if (prepared == Preparation.BLOCKED) {
                return Result.blocked(nonblank(environment.preparationFailure(), "PATH_UNREACHABLE"), session, target);
            }
            String failure = environment.place(target, support.position, support.face, selected, block.state());
            if (failure != null) return Result.blocked(failure, session, target);
            WorldBlock placed = environment.block(target);
            if (!matches(placed, block, selected)) return Result.blocked("UNCERTAIN_EFFECT", session, target);
            session.completed.add(index);
            session.ticksWithoutProgress = 0;
            return running(session, target);
        }

        if (session.plan.temporarySupport().cleanup() && !session.temporarySupports.isEmpty()) {
            var support = session.temporarySupports.entrySet().iterator().next();
            return cleanupSupport(session, environment, support);
        }

        for (SmallBlueprint.IndexedBlock indexed : session.plan.buildOrder()) {
            Position target = absolute(session.plan.anchor(), indexed.block().offset());
            if (!matches(environment.block(target), indexed.block(), session.selections.get(indexed.index()))) {
                session.completed.remove(indexed.index());
                session.reconciled = false;
                return running(session, target);
            }
        }
        return new Result(Status.COMPLETE, "BLUEPRINT_COMPLETE", session.completed.size(),
                session.plan.blocks().size(), session.temporarySupports.size(), null);
    }

    private static Result placeTemporarySupport(Session session, Environment environment, Position target) {
        SmallBlueprint.SupportPolicy policy = session.plan.temporarySupport();
        if (policy.maxBlocks() == 0 || session.supportsPlaced >= policy.maxBlocks()) {
            return Result.blocked("TEMPORARY_SUPPORT_BUDGET_EXCEEDED", session, target);
        }
        Position temporary = target.offset(Direction.DOWN);
        if (blueprintContains(session.plan, temporary)) {
            return Result.blocked("PLACEMENT_SUPPORT_MISSING", session, target);
        }
        if (environment.playerOccupies(temporary)) return Result.blocked("PLAYER_OCCUPIED", session, temporary);
        WorldBlock existing = environment.block(temporary);
        if (!existing.loaded()) return Result.blocked("TARGET_CHUNK_UNLOADED", session, temporary);
        if (!existing.replaceable()) return null;
        Position base = temporary.offset(Direction.DOWN);
        WorldBlock baseBlock = environment.block(base);
        if (!baseBlock.loaded()) return Result.blocked("TARGET_CHUNK_UNLOADED", session, base);
        if (baseBlock.replaceable()) return Result.blocked("TEMPORARY_SUPPORT_UNAVAILABLE", session, temporary);
        String selected = policy.blocks().stream()
                .filter(id -> environment.validMaterial(id, Map.of()) && environment.inventoryCount(id) > 0)
                .findFirst().orElse(null);
        if (selected == null) return Result.blocked("TEMPORARY_SUPPORT_MATERIALS_INSUFFICIENT", session, temporary);
        Preparation prepared = environment.prepare(temporary, base);
        if (prepared == Preparation.WAIT) return running(session, temporary);
        if (prepared == Preparation.BLOCKED) {
            return Result.blocked(nonblank(environment.preparationFailure(), "PATH_UNREACHABLE"), session, temporary);
        }
        String failure = environment.place(temporary, base, Direction.UP, selected, Map.of());
        if (failure != null) return Result.blocked(failure, session, temporary);
        if (!environment.block(temporary).id().equals(selected)) {
            return Result.blocked("UNCERTAIN_EFFECT", session, temporary);
        }
        session.temporarySupports.put(temporary, selected);
        session.supportsPlaced++;
        session.ticksWithoutProgress = 0;
        return running(session, temporary);
    }

    private static void reconcile(Session session, Environment environment) {
        session.completed.removeIf(index -> {
            SmallBlueprint.Block block = session.plan.blocks().get(index);
            return !matches(environment.block(absolute(session.plan.anchor(), block.offset())),
                    block, session.selections.get(index));
        });
    }

    private static String selectMaterials(Session session, Environment environment) {
        Map<String, Integer> remaining = new HashMap<>();
        for (SmallBlueprint.IndexedBlock indexed : session.plan.buildOrder()) {
            if (session.completed.contains(indexed.index())) continue;
            SmallBlueprint.Block block = indexed.block();
            WorldBlock current = environment.block(absolute(session.plan.anchor(), block.offset()));
            String present = block.materials().stream()
                    .filter(candidate -> matches(current, block, candidate))
                    .findFirst().orElse(null);
            if (present != null) {
                session.selections.put(indexed.index(), present);
                session.completed.add(indexed.index());
                continue;
            }
            String retained = session.selections.get(indexed.index());
            if (retained != null && block.materials().contains(retained)
                    && environment.validMaterial(retained, block.state())) {
                int available = remaining.computeIfAbsent(retained, environment::inventoryCount);
                if (available > 0) {
                    remaining.put(retained, available - 1);
                    continue;
                }
            }
            String selected = null;
            for (String candidate : block.materials()) {
                if (!environment.validMaterial(candidate, block.state())) continue;
                int available = remaining.computeIfAbsent(candidate, environment::inventoryCount);
                if (available > 0) {
                    remaining.put(candidate, available - 1);
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) return "MATERIALS_INSUFFICIENT";
            session.selections.put(indexed.index(), selected);
        }
        return null;
    }

    private static Result cleanupSupport(Session session, Environment environment,
                                         Map.Entry<Position, String> support) {
        WorldBlock current = environment.block(support.getKey());
        if (!current.loaded()) return Result.blocked("TARGET_CHUNK_UNLOADED", session, support.getKey());
        if (!current.id().equals(support.getValue())) {
            if (current.replaceable()) {
                session.temporarySupports.remove(support.getKey());
                return running(session, support.getKey());
            }
            return Result.blocked("TEMPORARY_SUPPORT_CHANGED", session, support.getKey());
        }
        Preparation prepared = environment.prepare(support.getKey(), support.getKey());
        if (prepared == Preparation.WAIT) return running(session, support.getKey());
        if (prepared == Preparation.BLOCKED) {
            return Result.blocked(nonblank(environment.preparationFailure(), "PATH_UNREACHABLE"), session, support.getKey());
        }
        String failure = environment.remove(support.getKey(), support.getValue());
        if ("TEMPORARY_SUPPORT_BREAKING".equals(failure)) return running(session, support.getKey());
        if (failure != null) return Result.blocked(failure, session, support.getKey());
        session.temporarySupports.remove(support.getKey());
        session.ticksWithoutProgress = 0;
        return running(session, support.getKey());
    }

    private static boolean supportsCompletedBlock(Session session, Position support) {
        Position above = support.offset(Direction.UP);
        for (Integer index : session.completed) {
            SmallBlueprint.Block block = session.plan.blocks().get(index);
            if (absolute(session.plan.anchor(), block.offset()).equals(above)) return true;
        }
        return false;
    }

    private static Support findSupport(Position target, Environment environment) {
        for (Direction face : Direction.values()) {
            Position support = target.offset(face.opposite());
            WorldBlock block = environment.block(support);
            if (block.loaded() && !block.replaceable()) return new Support(support, face);
        }
        return null;
    }

    private static boolean matches(WorldBlock world, SmallBlueprint.Block block, String selected) {
        if (!world.loaded() || selected == null || !world.id().equals(selected)) return false;
        return block.state().entrySet().stream().allMatch(property ->
                property.getValue().equals(world.state().get(property.getKey())));
    }

    private static boolean blueprintContains(SmallBlueprint plan, Position absolute) {
        return plan.blocks().stream().map(block -> absolute(plan.anchor(), block.offset())).anyMatch(absolute::equals);
    }

    private static Position absolute(SmallBlueprint.Anchor anchor, SmallBlueprint.Offset offset) {
        return new Position(anchor.x() + offset.x(), anchor.y() + offset.y(), anchor.z() + offset.z());
    }

    private static Result running(Session session, Position position) {
        return new Result(Status.RUNNING, "BLUEPRINT_RUNNING", session.completed.size(),
                session.plan.blocks().size(), session.temporarySupports.size(), position);
    }

    private static String nonblank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Support(Position position, Direction face) { }
}
