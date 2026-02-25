package com.playstudio.bridgemod.pathfinding.moves;

import com.playstudio.bridgemod.pathfinding.ActionCosts;
import com.playstudio.bridgemod.pathfinding.CalculationContext;
import com.playstudio.bridgemod.pathfinding.PrecomputedData;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Block passability and walkability checks.
 * Ported from Baritone's MovementHelper.java (subset for Phase 3B).
 *
 * Uses PrecomputedData for O(1) block state flag lookups,
 * eliminating instanceof chains for ~85% of calls.
 * Only falls through to position-dependent checks for MAYBE states.
 *
 * Omits: door/gate passability, block placement helpers, tool selection,
 * avoidBreaking, fullyPassable. getMiningDurationTicks returns COST_INF
 * for non-passable blocks (no actual mining in Phase 3B).
 */
public final class MovementHelper {

    private MovementHelper() {}

    // ==================== canWalkThrough ====================

    /**
     * Can an entity pass through this block position?
     * Ported from Baritone's canWalkThrough + canWalkThroughBlockState.
     */
    public static boolean canWalkThrough(CalculationContext ctx, int x, int y, int z) {
        BlockState state = ctx.get(x, y, z);
        return canWalkThrough(ctx, x, y, z, state);
    }

    public static boolean canWalkThrough(CalculationContext ctx, int x, int y, int z, BlockState state) {
        int flags = ctx.precomputed.getFlags(state);
        int wth = flags & PrecomputedData.WTH_MASK;
        if (wth == PrecomputedData.WTH_YES) return true;
        if (wth == PrecomputedData.WTH_NO) return false;
        // MAYBE: position-dependent collision shape check
        return ctx.isPassable(x, y, z);
    }

    // ==================== canWalkOn ====================

    /**
     * Can an entity stand on this block?
     * Ported from Baritone's canWalkOn + canWalkOnBlockState.
     */
    public static boolean canWalkOn(CalculationContext ctx, int x, int y, int z) {
        BlockState state = ctx.get(x, y, z);
        return canWalkOn(ctx, x, y, z, state);
    }

    public static boolean canWalkOn(CalculationContext ctx, int x, int y, int z, BlockState state) {
        int flags = ctx.precomputed.getFlags(state);
        int won = flags & PrecomputedData.WON_MASK;
        if (won == PrecomputedData.WON_YES) return true;
        if (won == PrecomputedData.WON_NO) return false;
        // MAYBE: position-dependent isFullBlock check (rare - barrier blocks, etc.)
        return ctx.isFullBlock(x, y, z);
    }

    /**
     * Baritone's mustBeSolidToWalkOn - checks if we're standing on a full block
     * (as opposed to standing on water with lily pad, etc.)
     */
    public static boolean mustBeSolidToWalkOn(CalculationContext ctx, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof LadderBlock || block instanceof VineBlock) return false;
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) return false;
        return true;
    }

    // ==================== Utility Methods ====================

    /**
     * Is this block a water block?
     */
    public static boolean isWater(Block block) {
        return block == Blocks.WATER;
    }

    public static boolean isWater(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER);
    }

    public static boolean isWater(CalculationContext ctx, int x, int y, int z) {
        return isWater(ctx.get(x, y, z));
    }

    /**
     * Is this block a lava block?
     */
    public static boolean isLava(Block block) {
        return block == Blocks.LAVA;
    }

    public static boolean isLava(CalculationContext ctx, int x, int y, int z) {
        FluidState fluid = ctx.get(x, y, z).getFluidState();
        return fluid.is(Fluids.LAVA) || fluid.is(Fluids.FLOWING_LAVA);
    }

    /**
     * Is this block any liquid?
     */
    public static boolean isLiquid(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    /**
     * Is this block flowing (not source)?
     */
    public static boolean isFlowing(BlockState state) {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && !fluid.isSource();
    }

    /**
     * Should we avoid walking into this block? (Baritone's avoidWalkingInto)
     */
    public static boolean avoidWalkingInto(Block block) {
        if (block == Blocks.MAGMA_BLOCK) return true;
        if (block instanceof CactusBlock) return true;
        if (block instanceof FireBlock || block instanceof BaseFireBlock) return true;
        if (block == Blocks.LAVA) return true;
        return false;
    }

    public static boolean avoidWalkingInto(BlockState state) {
        return avoidWalkingInto(state.getBlock());
    }

    /**
     * Is this a bottom slab?
     */
    public static boolean isBottomSlab(BlockState state) {
        if (!(state.getBlock() instanceof SlabBlock)) return false;
        return state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    /**
     * Is this block soul sand? (slows walking)
     */
    public static boolean isSoulSand(Block block) {
        return block == Blocks.SOUL_SAND;
    }

    /**
     * Get the mining duration in ticks to break a block.
     * Phase 3B: returns 0 if passable, COST_INF if not (no mining).
     * Phase 3C will implement actual mining duration calculation.
     *
     * @param includesFalling if true, account for blocks that might fall when broken
     */
    public static double getMiningDurationTicks(CalculationContext ctx, int x, int y, int z,
                                                 BlockState state, boolean includesFalling) {
        if (canWalkThrough(ctx, x, y, z, state)) {
            return 0;  // Already passable, no mining needed
        }
        // Phase 3B: don't mine, treat as impassable
        return ActionCosts.COST_INF;
    }
}
