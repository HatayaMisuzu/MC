package com.mccompanion.core.body.daily.navigation;

import com.mccompanion.core.navigation.GridPathPlanner;

/** Integer navigation cell with no Minecraft dependency. */
public record NavPoint(int x, int y, int z) implements Comparable<NavPoint> {
    public Vec center() {
        return new Vec(x + 0.5, y, z + 0.5);
    }

    public int manhattanDistance(NavPoint other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }

    public GridPathPlanner.Point plannerPoint() {
        return new GridPathPlanner.Point(x, y, z);
    }

    public static NavPoint from(GridPathPlanner.Point point) {
        return new NavPoint(point.x(), point.y(), point.z());
    }

    @Override
    public int compareTo(NavPoint other) {
        int xOrder = Integer.compare(x, other.x);
        if (xOrder != 0) return xOrder;
        int yOrder = Integer.compare(y, other.y);
        return yOrder != 0 ? yOrder : Integer.compare(z, other.z);
    }
}
