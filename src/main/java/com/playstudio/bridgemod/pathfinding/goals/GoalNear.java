package com.playstudio.bridgemod.pathfinding.goals;

/**
 * Goal: get within a certain range of a target position.
 * 100% ported from Baritone's GoalNear.
 */
public class GoalNear implements Goal {

    private final int x;
    private final int y;
    private final int z;
    private final int rangeSq;

    public GoalNear(int x, int y, int z, int range) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.rangeSq = range * range;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff <= rangeSq;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return GoalBlock.calculate(xDiff, yDiff, zDiff);
    }

    @Override
    public String toString() {
        return "GoalNear{" + x + ", " + y + ", " + z + ", range=" + (int) Math.sqrt(rangeSq) + "}";
    }
}
