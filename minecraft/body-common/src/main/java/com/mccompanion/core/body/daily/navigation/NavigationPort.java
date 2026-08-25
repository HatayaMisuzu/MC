package com.mccompanion.core.body.daily.navigation;

import com.mccompanion.core.navigation.GridPathPlanner;

/**
 * The small version adapter surface required by {@link DailyNavigator}.
 *
 * <p>Platform modules translate Minecraft positions, collision checks, door interaction and
 * movement input at this boundary. The controller never receives a Minecraft object and never
 * edits a world itself.
 */
public interface NavigationPort {
    /** Current physical body position. */
    Vec currentPosition();

    /** Current integer navigation cell. */
    default NavPoint currentPoint() {
        Vec position = currentPosition();
        return new NavPoint((int) Math.floor(position.x()), (int) Math.floor(position.y()),
                (int) Math.floor(position.z()));
    }

    /** Current world/dimension identity; changing it invalidates the action. */
    default String worldKey() {
        return "";
    }

    /** Version-specific bounded route construction over observed traversability. */
    default GridPathPlanner.Plan plan(NavPoint from, NavPoint target) {
        return GridPathPlanner.plan(from.plannerPoint(), target.plannerPoint(),
                new GridPathPlanner.Environment() {
                    @Override
                    public boolean loaded(GridPathPlanner.Point point) {
                        return true;
                    }

                    @Override
                    public GridPathPlanner.Traversal traversal(
                            GridPathPlanner.Point previous, GridPathPlanner.Point next) {
                        return traversable(NavPoint.from(previous), NavPoint.from(next))
                                ? GridPathPlanner.Traversal.passable(1.0D)
                                : GridPathPlanner.Traversal.blocked();
                    }
                });
    }

    /** Whether the observed edge is still safe and traversable. */
    boolean traversable(NavPoint from, NavPoint to);

    /** Opens an observed door/portal at the next cell through the platform's vanilla action. */
    default boolean openDoor(NavPoint point) {
        return false;
    }

    /** Applies one bounded movement input; the adapter owns the Minecraft input details. */
    void applyMove(Vec direction, boolean jump);

    /** Stops horizontal movement without changing world/entity state. */
    void stop();
}
