package com.playstudio.bridgemod.pathfinding.goals;

/**
 * An abstract Goal for pathing, can be anything from a specific block to just a Y coordinate.
 * 100% ported from Baritone's Goal interface.
 */
public interface Goal {

    /**
     * Returns whether or not the specified position meets the requirement for this goal.
     */
    boolean isInGoal(int x, int y, int z);

    /**
     * Estimate the number of ticks it will take to get to the goal.
     * Must be admissible (never overestimate) for A* optimality.
     */
    double heuristic(int x, int y, int z);

    /**
     * Returns the heuristic at the goal.
     * i.e. heuristic() == heuristic(x,y,z) when isInGoal(x,y,z) == true
     * Some Goals do not have a heuristic of 0 when at the goal position.
     */
    default double heuristic() {
        return 0;
    }
}
