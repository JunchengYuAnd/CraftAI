package com.playstudio.bridgemod.pathfinding;

import com.playstudio.bridgemod.pathfinding.goals.Goal;
import com.playstudio.bridgemod.pathfinding.moves.Moves;

/**
 * A node in the A* search graph, representing a block position.
 * 100% ported from Baritone's PathNode.
 */
public final class PathNode {

    public final int x;
    public final int y;
    public final int z;

    /**
     * Cached, should always be equal to goal.heuristic(pos)
     */
    public final double estimatedCostToGoal;

    /**
     * Total cost of getting from start to here.
     * Mutable and changed by PathFinder.
     */
    public double cost;

    /**
     * Should always be equal to estimatedCostToGoal + cost.
     * Mutable and changed by PathFinder.
     */
    public double combinedCost;

    /**
     * In the graph search, what previous node contributed to the cost.
     * Mutable and changed by PathFinder.
     */
    public PathNode previous;

    /**
     * The movement type that led to this node from its parent.
     * Used by PathExecutor to create the correct Movement execution object.
     * Null for the start node.
     */
    public Moves moveType;

    /**
     * Where is this node in the array flattenization of the binary heap?
     * Needed for decrease-key operations.
     */
    public int heapPosition;

    public PathNode(int x, int y, int z, Goal goal) {
        this.previous = null;
        this.cost = ActionCosts.COST_INF;
        this.estimatedCostToGoal = goal.heuristic(x, y, z);
        if (Double.isNaN(estimatedCostToGoal)) {
            throw new IllegalStateException(goal + " calculated implausible heuristic");
        }
        this.heapPosition = -1;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean isOpen() {
        return heapPosition != -1;
    }

    @Override
    public int hashCode() {
        return (int) longHash(x, y, z);
    }

    @Override
    public boolean equals(Object obj) {
        // GOTTA GO FAST (Baritone skips null/type checks for speed)
        final PathNode other = (PathNode) obj;
        return x == other.x && y == other.y && z == other.z;
    }

    /**
     * Polynomial hash for block position.
     * Ported from Baritone's BetterBlockPos.longHash.
     *
     * Uses polynomial rolling hash instead of bit-packing because it has
     * better distribution for open-addressing hash maps (Long2ObjectOpenHashMap).
     * Bit-packing creates clustering since nearby coordinates produce nearby hashes.
     */
    public static long longHash(int x, int y, int z) {
        long hash = 3241;
        hash = 3457689L * hash + x;
        hash = 8734625L * hash + y;
        hash = 2873465L * hash + z;
        return hash;
    }
}
