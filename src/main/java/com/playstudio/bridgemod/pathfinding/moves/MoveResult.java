package com.playstudio.bridgemod.pathfinding.moves;

import com.playstudio.bridgemod.pathfinding.ActionCosts;

/**
 * Mutable result of a movement cost calculation.
 * 100% ported from Baritone's MutableMoveResult.
 * Reused to avoid GC pressure during A* search.
 */
public final class MoveResult {

    public int x;
    public int y;
    public int z;
    public double cost;

    public MoveResult() {
        reset();
    }

    public void reset() {
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.cost = ActionCosts.COST_INF;
    }

    public boolean isPossible() {
        return cost < ActionCosts.COST_INF;
    }
}
