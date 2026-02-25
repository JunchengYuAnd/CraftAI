package com.playstudio.bridgemod.pathfinding.goals;

import com.playstudio.bridgemod.pathfinding.ActionCosts;

/**
 * A specific block position goal.
 * 100% ported from Baritone's GoalBlock.
 * Heuristic = GoalYLevel.calculate(yDiff) + GoalXZ.calculate(xDiff, zDiff)
 */
public class GoalBlock implements Goal {

    public final int x;
    public final int y;
    public final int z;

    public GoalBlock(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && y == this.y && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return calculate(xDiff, yDiff, zDiff);
    }

    /**
     * Combined heuristic from Baritone's GoalBlock.calculate.
     * Uses GoalYLevel for vertical + GoalXZ for horizontal.
     */
    public static double calculate(double xDiff, int yDiff, double zDiff) {
        double heuristic = 0;
        // Vertical component (GoalYLevel.calculate)
        heuristic += calculateYLevel(yDiff);
        // Horizontal component (GoalXZ.calculate)
        heuristic += calculateXZ(xDiff, zDiff);
        return heuristic;
    }

    /**
     * Ported from Baritone's GoalYLevel.calculate.
     * yDiff = currentY - goalY: positive means above goal, negative means below.
     */
    static double calculateYLevel(int yDiff) {
        if (yDiff > 0) {
            // Above goal, need to fall down
            return ActionCosts.FALL_N_BLOCKS_COST[yDiff];
        }
        // Below goal, need to jump up
        // Each block up costs jump + walk
        return (double) -yDiff * ActionCosts.JUMP_ONE_BLOCK_COST + ActionCosts.WALK_ONE_BLOCK_COST;
    }

    /**
     * Ported from Baritone's GoalXZ.calculate.
     * Octile distance (diagonal + straight) scaled by costHeuristic.
     */
    static double calculateXZ(double xDiff, double zDiff) {
        double x = Math.abs(xDiff);
        double z = Math.abs(zDiff);
        double straight;
        double diagonal;
        if (x < z) {
            straight = z - x;
            diagonal = x;
        } else {
            straight = x - z;
            diagonal = z;
        }
        diagonal *= ActionCosts.SQRT_2;
        // Baritone uses costHeuristic setting (default = 3.563, which is SPRINT_ONE_BLOCK_COST)
        return (diagonal + straight) * ActionCosts.SPRINT_ONE_BLOCK_COST;
    }

    @Override
    public String toString() {
        return "GoalBlock{" + x + ", " + y + ", " + z + "}";
    }
}
