package com.playstudio.bridgemod.pathfinding;

/**
 * Movement cost constants.
 * 100% ported from Baritone's ActionCosts interface.
 * All costs are measured roughly in game ticks (20 ticks = 1 second).
 */
public interface ActionCosts {

    double WALK_ONE_BLOCK_COST = 20 / 4.317;  // 4.633
    double WALK_ONE_IN_WATER_COST = 20 / 2.2;  // 9.091
    double WALK_ONE_OVER_SOUL_SAND_COST = WALK_ONE_BLOCK_COST * 2;  // ~9.266
    double LADDER_UP_ONE_COST = 20 / 2.35;  // 8.511
    double LADDER_DOWN_ONE_COST = 20 / 3.0;  // 6.667
    double SNEAK_ONE_BLOCK_COST = 20 / 1.3;  // 15.385
    double SPRINT_ONE_BLOCK_COST = 20 / 5.612;  // 3.564
    double SPRINT_MULTIPLIER = SPRINT_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST;  // ~0.769
    double PLACE_ONE_BLOCK_COST = 20.0;  // estimated ticks for bridge/pillar block placement

    /**
     * Estimated mining cost per vertical step for the Y-descent heuristic.
     * Each descent step in a staircase mines ~2 new blocks (some overlap between zigzag steps).
     * With iron pickaxe on stone: ~6.7 ticks/block × 2 ≈ 13 ticks.
     * With stone pickaxe: ~10 ticks/block × 2 ≈ 20 ticks.
     *
     * We use 10 as a moderate estimate. This gives per-block descent heuristic ≈ 19.3:
     * - Underground: sufficient for bestSoFar(coeff=10) to track descent nodes
     *   (metric improvement = 19.3 - cost/10 > 0 for cost < 193)
     * - Surface: overestimates by ~2x (19.3 vs actual 9.3), acceptable bounded-suboptimal
     *   (a value of 20 caused 3x overestimate → bot mined through hills instead of walking)
     */
    double MINE_COST_HEURISTIC = 10.0;

    /**
     * To walk off an edge you need to walk 0.5 to the edge then 0.3 to start falling off
     */
    double WALK_OFF_BLOCK_COST = WALK_ONE_BLOCK_COST * 0.8;  // 3.706

    /**
     * To walk the rest of the way to be centered on the new block
     */
    double CENTER_AFTER_FALL_COST = WALK_ONE_BLOCK_COST - WALK_OFF_BLOCK_COST;  // 0.927

    /**
     * don't make this Double.MAX_VALUE because it's added to other things, maybe other COST_INFs,
     * and that would make it overflow to negative
     */
    double COST_INF = 1_000_000;

    double[] FALL_N_BLOCKS_COST = generateFallNBlocksCost();

    double FALL_1_25_BLOCKS_COST = distanceToTicks(1.25);
    double FALL_0_25_BLOCKS_COST = distanceToTicks(0.25);

    /**
     * Jump cost derived from MC physics.
     * When you hit space, you get enough upward velocity to go 1.25 blocks.
     * Then you fall the remaining 0.25 to land on the surface one block higher.
     * Since parabolas are symmetric, JUMP_ONE_BLOCK_COST = FALL_1_25 - FALL_0_25.
     */
    double JUMP_ONE_BLOCK_COST = FALL_1_25_BLOCKS_COST - FALL_0_25_BLOCKS_COST;

    double SQRT_2 = Math.sqrt(2);

    static double[] generateFallNBlocksCost() {
        double[] costs = new double[257];
        for (int i = 0; i < 257; i++) {
            costs[i] = distanceToTicks(i);
        }
        return costs;
    }

    static double velocity(int ticks) {
        return (Math.pow(0.98, ticks) - 1) * -3.92;
    }

    static double distanceToTicks(double distance) {
        if (distance == 0) {
            return 0; // Avoid 0/0 NaN
        }
        double tmpDistance = distance;
        int tickCount = 0;
        while (true) {
            double fallDistance = velocity(tickCount);
            if (tmpDistance <= fallDistance) {
                return tickCount + tmpDistance / fallDistance;
            }
            tmpDistance -= fallDistance;
            tickCount++;
        }
    }
}
