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
    public static final Limits DEFAULT_LIMITS = new Limits(192, 32, 8_192);
    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
        {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
        {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1},
        {0, 1, 0}, {0, -1, 0}
    };

    private GridPathPlanner() {}

    public static Plan plan(Point start, Point goal, Environment environment) {
        return plan(start, goal, environment, DEFAULT_LIMITS);
    }

    public static Plan plan(Point start, Point goal, Environment environment, Limits limits) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(limits, "limits");
        if (!environment.loaded(goal)) return new Plan(Status.TARGET_UNLOADED, List.of(), 0);
        if (horizontalDistance(start, goal) > limits.maxHorizontalDistance()
                || Math.abs(start.y() - goal.y()) > limits.maxVerticalDistance()) {
            return new Plan(Status.OUT_OF_RANGE, List.of(), 0);
        }
        if (start.equals(goal)) return new Plan(Status.READY, List.of(), 1);

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator
                .comparingDouble(Node::estimatedTotal)
                .thenComparingDouble(Node::cost)
                .thenComparing(node -> node.point()));
        Map<Point, Double> best = new HashMap<>();
        Map<Point, Point> parents = new HashMap<>();
        Set<Point> closed = new HashSet<>();
        best.put(start, 0.0D);
        open.add(new Node(start, 0.0D, heuristic(start, goal)));

        int explored = 0;
        while (!open.isEmpty() && explored < limits.maxExploredNodes()) {
            Node current = open.remove();
            if (!closed.add(current.point())) continue;
            explored++;
            if (current.point().equals(goal)) {
                return new Plan(Status.READY, reconstruct(start, goal, parents), explored);
            }
            for (int[] offset : OFFSETS) {
                Point next = current.point().offset(offset[0], offset[1], offset[2]);
                if (closed.contains(next)
                        || horizontalDistance(start, next) > limits.maxHorizontalDistance()
                        || Math.abs(start.y() - next.y()) > limits.maxVerticalDistance()
                        || !environment.loaded(next)) {
                    continue;
                }
                Traversal traversal = environment.traversal(current.point(), next);
                if (!traversal.passable()) continue;
                double candidate = current.cost() + traversal.cost();
                if (candidate + 1.0e-9D >= best.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                best.put(next, candidate);
                parents.put(next, current.point());
                open.add(new Node(next, candidate, candidate + heuristic(next, goal)));
            }
        }
        return new Plan(Status.UNREACHABLE, List.of(), explored);
    }

    private static List<Point> reconstruct(Point start, Point goal, Map<Point, Point> parents) {
        ArrayList<Point> reversed = new ArrayList<>();
        Point cursor = goal;
        while (!cursor.equals(start)) {
            reversed.add(cursor);
            cursor = parents.get(cursor);
            if (cursor == null) return List.of();
        }
        ArrayList<Point> route = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) route.add(reversed.get(index));
        return List.copyOf(route);
    }

    private static double heuristic(Point first, Point second) {
        return Math.abs(first.x() - second.x())
                + Math.abs(first.z() - second.z())
                + 1.25D * Math.abs(first.y() - second.y());
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

    public record Traversal(boolean passable, double cost) {
        public Traversal {
            if (passable && (!(cost > 0.0D) || !Double.isFinite(cost))) {
                throw new IllegalArgumentException("Passable traversal cost must be finite and positive");
            }
        }

        public static Traversal blocked() {
            return new Traversal(false, Double.POSITIVE_INFINITY);
        }

        public static Traversal passable(double cost) {
            return new Traversal(true, cost);
        }
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
        UNREACHABLE
    }

    public record Plan(Status status, List<Point> points, int exploredNodes) {
        public Plan {
            Objects.requireNonNull(status, "status");
            points = List.copyOf(points);
            if (exploredNodes < 0) throw new IllegalArgumentException("exploredNodes must not be negative");
        }
    }

    private record Node(Point point, double cost, double estimatedTotal) {}
}
