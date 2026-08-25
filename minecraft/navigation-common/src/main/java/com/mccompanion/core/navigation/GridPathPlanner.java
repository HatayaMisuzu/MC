package com.mccompanion.core.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Bounded deterministic A* over externally observed traversal cells.
 *
 * <p>The environment owns all game-specific collision and safety decisions. This class only
 * composes those bounded observations into a route; it does not choose a destination or task.
 */
public final class GridPathPlanner {
    public static final Limits DEFAULT_LIMITS = new Limits(512, 64, 32_768);
    public static final Budget DEFAULT_BUDGET = Budget.noWorldChanges();
    private static final double MIN_STEP_COST = 0.25D;
    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
        {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
        {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1},
        {1, -2, 0}, {-1, -2, 0}, {0, -2, 1}, {0, -2, -1},
        {1, -3, 0}, {-1, -3, 0}, {0, -3, 1}, {0, -3, -1},
        {1, -4, 0}, {-1, -4, 0}, {0, -4, 1}, {0, -4, -1},
        {2, 0, 0}, {-2, 0, 0}, {0, 0, 2}, {0, 0, -2},
        {0, 1, 0}, {0, -1, 0}
    };

    private GridPathPlanner() {}

    public static Plan plan(Point start, Point goal, Environment environment) {
        return plan(start, goal, environment, DEFAULT_LIMITS);
    }

    public static Plan plan(Point start, Point goal, Environment environment, Limits limits) {
        return plan(start, goal, environment, limits, DEFAULT_BUDGET);
    }

    public static Plan plan(Point start, Point goal, Environment environment, Limits limits,
                            Budget budget) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(budget, "budget");
        if (!environment.loaded(goal)) return new Plan(Status.TARGET_UNLOADED, List.of(), 0);
        if (horizontalDistance(start, goal) > limits.maxHorizontalDistance()
                || Math.abs(start.y() - goal.y()) > limits.maxVerticalDistance()) {
            return new Plan(Status.OUT_OF_RANGE, List.of(), 0);
        }
        if (start.equals(goal)) return new Plan(Status.READY, List.of(), 1);

        SearchState startState = new SearchState(start, 0, 0, 0, 0);
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator
                .comparingDouble(Node::estimatedTotal)
                .thenComparingDouble(Node::cost)
                .thenComparing(node -> node.state().point())
                .thenComparingInt(node -> node.state().brokenBlocks())
                .thenComparingInt(node -> node.state().placedBlocks())
                .thenComparingInt(node -> node.state().riskUnits())
                .thenComparingInt(node -> node.state().actionSteps()));
        Map<SearchState, Double> best = new HashMap<>();
        Map<SearchState, SearchState> parents = new HashMap<>();
        Map<SearchState, Traversal> traversals = new HashMap<>();
        Set<SearchState> closed = new HashSet<>();
        best.put(startState, 0.0D);
        open.add(new Node(startState, 0.0D, heuristic(start, goal)));

        int explored = 0;
        boolean budgetRejected = false;
        while (!open.isEmpty() && explored < limits.maxExploredNodes()) {
            Node current = open.remove();
            if (!closed.add(current.state())) continue;
            explored++;
            if (current.state().point().equals(goal)) {
                List<RouteStep> steps = reconstruct(startState, current.state(), parents, traversals);
                return Plan.ready(steps, explored, current.cost(), current.state().use());
            }
            for (int[] offset : OFFSETS) {
                Point next = current.state().point().offset(offset[0], offset[1], offset[2]);
                if (horizontalDistance(start, next) > limits.maxHorizontalDistance()
                        || Math.abs(start.y() - next.y()) > limits.maxVerticalDistance()
                        || !environment.loaded(next)) {
                    continue;
                }
                Traversal traversal = environment.traversal(current.state().point(), next);
                if (!traversal.passable()) continue;
                SearchState nextState = current.state().advance(next, traversal);
                if (!budget.allows(nextState.use())) {
                    budgetRejected = true;
                    continue;
                }
                if (closed.contains(nextState)
                        || horizontalDistance(start, next) > limits.maxHorizontalDistance()
                        || Math.abs(start.y() - next.y()) > limits.maxVerticalDistance()) continue;
                double candidate = current.cost() + traversal.cost();
                if (candidate + 1.0e-9D >= best.getOrDefault(nextState, Double.POSITIVE_INFINITY)) continue;
                best.put(nextState, candidate);
                parents.put(nextState, current.state());
                traversals.put(nextState, traversal);
                open.add(new Node(nextState, candidate, candidate + heuristic(next, goal)));
            }
        }
        return new Plan(budgetRejected ? Status.BUDGET_EXCEEDED : Status.UNREACHABLE, List.of(), explored);
    }

    private static List<RouteStep> reconstruct(SearchState start, SearchState goal,
                                               Map<SearchState, SearchState> parents,
                                               Map<SearchState, Traversal> traversals) {
        ArrayList<RouteStep> reversed = new ArrayList<>();
        SearchState cursor = goal;
        while (!cursor.equals(start)) {
            Traversal traversal = traversals.get(cursor);
            if (traversal == null) return List.of();
            reversed.add(new RouteStep(cursor.point(), traversal.movement(), traversal.actions(),
                    traversal.cost(), traversal.riskUnits()));
            cursor = parents.get(cursor);
            if (cursor == null) return List.of();
        }
        ArrayList<RouteStep> route = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) route.add(reversed.get(index));
        return List.copyOf(route);
    }

    private static double heuristic(Point first, Point second) {
        return MIN_STEP_COST * (Math.abs(first.x() - second.x())
                + Math.abs(first.z() - second.z())
                + Math.abs(first.y() - second.y()));
    }

    private static int horizontalDistance(Point first, Point second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.z() - second.z());
    }

    public interface Environment {
        boolean loaded(Point point);

        Traversal traversal(Point from, Point to);
    }

    public record Point(int x, int y, int z) implements Comparable<Point> {
        public Point offset(int xOffset, int yOffset, int zOffset) {
            return new Point(x + xOffset, y + yOffset, z + zOffset);
        }

        @Override
        public int compareTo(Point other) {
            int xOrder = Integer.compare(x, other.x);
            if (xOrder != 0) return xOrder;
            int yOrder = Integer.compare(y, other.y);
            return yOrder != 0 ? yOrder : Integer.compare(z, other.z);
        }
    }

    public enum Movement {
        WALK,
        SPRINT,
        JUMP,
        STEP_UP,
        DROP,
        SWIM,
        CLIMB,
        OPEN_DOOR,
        BREAK_AND_MOVE,
        PLACE_SUPPORT,
        BRIDGE,
        PILLAR,
        JUMP_GAP
    }

    public enum ActionType {
        OPEN_DOOR,
        BREAK_BLOCK,
        PLACE_BLOCK
    }

    public record WorldAction(ActionType type, Point position, String expectedBlockId) {
        public WorldAction {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(position, "position");
            expectedBlockId = expectedBlockId == null ? "" : expectedBlockId;
        }
    }

    public record Traversal(boolean passable, double cost, Movement movement,
                            List<WorldAction> actions, int riskUnits) {
        public Traversal(boolean passable, double cost) {
            this(passable, cost, Movement.WALK, List.of(), 0);
        }

        public Traversal {
            movement = movement == null ? Movement.WALK : movement;
            actions = actions == null ? List.of() : List.copyOf(actions);
            if (passable && (!(cost >= MIN_STEP_COST) || !Double.isFinite(cost))) {
                throw new IllegalArgumentException("Passable traversal cost is below the admissible minimum");
            }
            if (!passable && (!actions.isEmpty() || riskUnits != 0)) {
                throw new IllegalArgumentException("Blocked traversal cannot carry actions or risk");
            }
            if (actions.size() > 8 || riskUnits < 0 || riskUnits > 1_000) {
                throw new IllegalArgumentException("Traversal action/risk bounds exceeded");
            }
        }

        public static Traversal blocked() {
            return new Traversal(false, Double.POSITIVE_INFINITY, Movement.WALK, List.of(), 0);
        }

        public static Traversal passable(double cost) {
            return new Traversal(true, cost, Movement.WALK, List.of(), 0);
        }

        public static Traversal move(Movement movement, double cost, int riskUnits) {
            return new Traversal(true, cost, movement, List.of(), riskUnits);
        }

        public static Traversal withActions(Movement movement, double cost, int riskUnits,
                                            List<WorldAction> actions) {
            return new Traversal(true, cost, movement, actions, riskUnits);
        }

        public int brokenBlocks() {
            return (int) actions.stream().filter(action -> action.type() == ActionType.BREAK_BLOCK).count();
        }

        public int placedBlocks() {
            return (int) actions.stream().filter(action -> action.type() == ActionType.PLACE_BLOCK).count();
        }
    }

    public record Budget(int maxBrokenBlocks, int maxPlacedBlocks, int maxRiskUnits,
                         int maxActionSteps) {
        public Budget {
            if (maxBrokenBlocks < 0 || maxPlacedBlocks < 0 || maxRiskUnits < 0
                    || maxActionSteps < 0) throw new IllegalArgumentException("negative navigation budget");
        }

        public static Budget noWorldChanges() {
            return new Budget(0, 0, 0, 256);
        }

        public static Budget safeLocomotion() {
            return new Budget(0, 0, 8, 256);
        }

        public boolean allows(BudgetUse use) {
            return use.brokenBlocks() <= maxBrokenBlocks && use.placedBlocks() <= maxPlacedBlocks
                    && use.riskUnits() <= maxRiskUnits && use.actionSteps() <= maxActionSteps;
        }
    }

    public record BudgetUse(int brokenBlocks, int placedBlocks, int riskUnits, int actionSteps) {
        public BudgetUse {
            if (brokenBlocks < 0 || placedBlocks < 0 || riskUnits < 0 || actionSteps < 0) {
                throw new IllegalArgumentException("negative navigation budget use");
            }
        }

        public static BudgetUse none() { return new BudgetUse(0, 0, 0, 0); }
    }

    public record Limits(int maxHorizontalDistance, int maxVerticalDistance, int maxExploredNodes) {
        public Limits {
            if (maxHorizontalDistance < 1 || maxVerticalDistance < 1 || maxExploredNodes < 1) {
                throw new IllegalArgumentException("Planner limits must be positive");
            }
        }
    }

    public enum Status {
        READY,
        TARGET_UNLOADED,
        OUT_OF_RANGE,
        BUDGET_EXCEEDED,
        UNREACHABLE
    }

    public record RouteStep(Point point, Movement movement, List<WorldAction> actions,
                            double cost, int riskUnits) {
        public RouteStep {
            Objects.requireNonNull(point, "point");
            movement = movement == null ? Movement.WALK : movement;
            actions = actions == null ? List.of() : List.copyOf(actions);
            if (!(cost >= MIN_STEP_COST) || !Double.isFinite(cost) || riskUnits < 0) {
                throw new IllegalArgumentException("invalid route step");
            }
        }
    }

    public record Plan(Status status, List<Point> points, int exploredNodes,
                       List<RouteStep> steps, double totalCost, BudgetUse budgetUse) {
        public Plan(Status status, List<Point> points, int exploredNodes) {
            this(status, points, exploredNodes,
                    status == Status.READY ? points.stream()
                            .map(point -> new RouteStep(point, Movement.WALK, List.of(), 1.0D, 0)).toList()
                            : List.of(), status == Status.READY ? points.size() : 0.0D, BudgetUse.none());
        }

        public Plan {
            Objects.requireNonNull(status, "status");
            points = List.copyOf(points);
            steps = steps == null ? List.of() : List.copyOf(steps);
            budgetUse = budgetUse == null ? BudgetUse.none() : budgetUse;
            if (exploredNodes < 0) throw new IllegalArgumentException("exploredNodes must not be negative");
            if (!Double.isFinite(totalCost) || totalCost < 0.0D) {
                throw new IllegalArgumentException("invalid total path cost");
            }
            if (status == Status.READY && !steps.stream().map(RouteStep::point).toList().equals(points)) {
                throw new IllegalArgumentException("route steps and points disagree");
            }
        }

        private static Plan ready(List<RouteStep> steps, int exploredNodes, double totalCost,
                                  BudgetUse budgetUse) {
            return new Plan(Status.READY, steps.stream().map(RouteStep::point).toList(), exploredNodes,
                    steps, totalCost, budgetUse);
        }
    }

    private record SearchState(Point point, int brokenBlocks, int placedBlocks,
                               int riskUnits, int actionSteps) {
        private SearchState advance(Point next, Traversal traversal) {
            return new SearchState(next, brokenBlocks + traversal.brokenBlocks(),
                    placedBlocks + traversal.placedBlocks(), riskUnits + traversal.riskUnits(),
                    actionSteps + traversal.actions().size());
        }

        private BudgetUse use() {
            return new BudgetUse(brokenBlocks, placedBlocks, riskUnits, actionSteps);
        }
    }

    private record Node(SearchState state, double cost, double estimatedTotal) {}
}
