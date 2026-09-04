package com.mccompanion.core.body.daily.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class DailyNavigatorTest {
    @Test
    void reachesGoalThroughTheSharedPort() {
        FakePort port = new FakePort();
        DailyNavigator navigator = new DailyNavigator(port, new DailyNavigator.Config(0.9, 3, 20, 100, 0.001));
        navigator.start(new NavPoint(4, 0, 0), 0);
        DailyNavigator.NavigationResult result = run(navigator, 50);
        assertEquals(DailyNavigator.Status.ARRIVED, result.status());
        assertTrue(port.moves > 0);
        assertTrue(port.openedDoors > 0);
    }

    @Test
    void defaultInteractionRadiusDoesNotSkipNonAdjacentWaypoints() {
        FakePort port = new FakePort();
        DailyNavigator navigator = new DailyNavigator(port);
        navigator.start(new NavPoint(6, 0, 0), 0);

        DailyNavigator.NavigationResult result = run(navigator, 80);

        assertEquals(DailyNavigator.Status.ARRIVED, result.status());
        assertTrue(port.moves >= 4);
    }

    @Test
    void routesBesideABlockedInteractionCell() {
        FakePort port = new FakePort();
        port.blocked.add(new NavPoint(5, 0, 0));
        DailyNavigator navigator = new DailyNavigator(port);
        navigator.start(new NavPoint(5, 0, 0), 0);

        DailyNavigator.NavigationResult result = run(navigator, 80);

        assertEquals(DailyNavigator.Status.ARRIVED, result.status());
        assertTrue(port.point.manhattanDistance(new NavPoint(5, 0, 0)) <= 1);
    }

    @Test
    void invalidatedRouteReplansAroundAnObservedObstacle() {
        FakePort port = new FakePort();
        DailyNavigator navigator = new DailyNavigator(port, new DailyNavigator.Config(0.9, 3, 20, 100, 0.001));
        navigator.start(new NavPoint(5, 0, 0), 0);
        navigator.tick(0);
        port.blocked.add(new NavPoint(2, 0, 0));
        DailyNavigator.NavigationResult result = run(navigator, 80);
        assertEquals(DailyNavigator.Status.ARRIVED, result.status());
        assertTrue(result.replans() >= 1);
        assertTrue(port.visited.stream().noneMatch(point -> point.equals(new NavPoint(2, 0, 0))));
    }

    @Test
    void dynamicGoalMovesAndTheControllerReplansLocally() {
        FakePort port = new FakePort();
        NavPoint[] target = {new NavPoint(3, 0, 0)};
        DailyNavigator navigator = new DailyNavigator(port, new DailyNavigator.Config(0.9, 4, 20, 100, 0.001));
        navigator.start(() -> new DailyNavigator.Goal(target[0], "overworld"), 0);
        navigator.tick(0);
        target[0] = new NavPoint(0, 0, 4);
        DailyNavigator.NavigationResult result = run(navigator, 100);
        assertEquals(DailyNavigator.Status.ARRIVED, result.status());
        assertTrue(result.replans() >= 1);
    }

    @Test
    void reportsStuckAfterBoundedRecoveryAttempts() {
        FakePort port = new FakePort();
        port.move = false;
        DailyNavigator navigator = new DailyNavigator(port, new DailyNavigator.Config(0.9, 2, 3, 100, 0.001));
        navigator.start(new NavPoint(4, 0, 0), 0);
        DailyNavigator.NavigationResult result = run(navigator, 30);
        assertEquals(DailyNavigator.Status.STUCK, result.status());
        assertEquals(2, result.replans());
    }

    @Test
    void pauseResumeAndCancelStopMovementWithoutLosingTheGoal() {
        FakePort port = new FakePort();
        DailyNavigator navigator = new DailyNavigator(port, new DailyNavigator.Config(0.9, 3, 20, 100, 0.001));
        navigator.start(new NavPoint(4, 0, 0), 0);
        navigator.pause(1);
        assertEquals(DailyNavigator.Status.PAUSED, navigator.tick(50).status());
        int moves = port.moves;
        navigator.resume(50);
        navigator.tick(51);
        assertTrue(port.moves > moves);
        assertEquals(DailyNavigator.Status.CANCELLED, navigator.cancel().status());
        assertTrue(port.stopped);
    }

    @Test
    void reportsTimeoutAndWorldChangeExplicitly() {
        FakePort port = new FakePort();
        DailyNavigator navigator = new DailyNavigator(port, new DailyNavigator.Config(0.9, 2, 20, 2, 0.001));
        navigator.start(new NavPoint(4, 0, 0), 0);
        assertEquals(DailyNavigator.Status.TIMEOUT, navigator.tick(3).status());

        FakePort changed = new FakePort();
        DailyNavigator worldNavigator = new DailyNavigator(changed);
        worldNavigator.start(new NavPoint(4, 0, 0), 0);
        changed.world = "nether";
        assertEquals(DailyNavigator.Status.WORLD_CHANGED, worldNavigator.tick(1).status());
    }

    @Test
    void snapshotRestoreKeepsRouteBudgetAndPauseState() {
        FakePort port = new FakePort();
        DailyNavigator navigator = new DailyNavigator(port, new DailyNavigator.Config(0.9, 3, 20, 20, 0.001));
        navigator.start(new NavPoint(6, 0, 0), 0);
        navigator.tick(0);
        navigator.pause(1);
        DailyNavigator.Snapshot snapshot = navigator.snapshot();

        FakePort restoredPort = new FakePort();
        DailyNavigator restored = new DailyNavigator(restoredPort,
                new DailyNavigator.Config(0.9, 3, 20, 20, 0.001));
        restored.restore(snapshot, () -> new DailyNavigator.Goal(new NavPoint(6, 0, 0), "overworld"));
        assertEquals(DailyNavigator.Status.PAUSED, restored.tick(100).status());
        restored.resume(100);
        assertEquals(DailyNavigator.Status.RUNNING, restored.tick(101).status());
    }

    private static DailyNavigator.NavigationResult run(DailyNavigator navigator, int ticks) {
        DailyNavigator.NavigationResult result = null;
        for (int tick = 0; tick < ticks; tick++) {
            result = navigator.tick(tick);
            if (result.status() != DailyNavigator.Status.RUNNING
                    && result.status() != DailyNavigator.Status.PAUSED) return result;
        }
        return result;
    }

    private static final class FakePort implements NavigationPort {
        NavPoint point = new NavPoint(0, 0, 0);
        String world = "overworld";
        final Set<NavPoint> blocked = new HashSet<>();
        final Set<NavPoint> visited = new HashSet<>();
        boolean move = true;
        boolean stopped;
        int moves;
        int openedDoors;

        @Override public Vec currentPosition() { return point.center(); }
        @Override public String worldKey() { return world; }
        @Override public boolean traversable(NavPoint from, NavPoint to) {
            return from.y() == to.y() && from.manhattanDistance(to) == 1 && !blocked.contains(to);
        }
        @Override public boolean openDoor(NavPoint next) { openedDoors++; return true; }
        @Override public void applyMove(Vec direction, boolean jump) {
            if (!move) return;
            int dx = (int) Math.signum(direction.x());
            int dz = (int) Math.signum(direction.z());
            NavPoint next = new NavPoint(point.x() + dx, point.y(), point.z() + dz);
            if (!blocked.contains(next)) { point = next; visited.add(point); }
            moves++;
        }
        @Override public void stop() { stopped = true; }
    }
}
