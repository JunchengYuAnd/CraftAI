package com.playstudio.bridgemod.pathfinding.moves;

import com.playstudio.bridgemod.pathfinding.ActionCosts;
import com.playstudio.bridgemod.pathfinding.CalculationContext;
import com.playstudio.bridgemod.pathfinding.PrecomputedData;
import net.minecraft.core.BlockPos;
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
        // MAYBE: position-dependent check
        // Baritone water logic: only still, surface (no water above), 1-block-deep water is passable.
        // Deep water (water above) acts as a wall → forces bot to swim at surface.
        if (state.getBlock() == Blocks.WATER) {
            if (isFlowing(ctx, x, y, z, state)) return false;
            if (ctx.assumeWalkOnWater) return false; // Jesus mode: water is solid
            BlockState above = ctx.get(x, y + 1, z);
            if (above.getBlock() == Blocks.WATER) return false; // deep water
            if (above.getBlock() instanceof WaterlilyBlock) return false; // lily pad covers water
            return true; // still, surface water — can wade through
        }
        // Non-water MAYBE: collision shape check
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
        // MAYBE: position-dependent check
        // Baritone water logic: deep water (water above this block) acts as walkable floor.
        // The bot "stands" on this block and swims at the surface level above.
        if (state.getBlock() == Blocks.WATER) {
            Block aboveBlock = ctx.get(x, y + 1, z).getBlock();
            // Lily pad / carpet on water: always walkable (Baritone explicit)
            if (aboveBlock instanceof WaterlilyBlock || aboveBlock instanceof CarpetBlock) return true;
            // Flowing water: only walkable if deep AND not in Jesus mode
            if (isFlowing(ctx, x, y, z, state)) {
                return isWater(aboveBlock) && !ctx.assumeWalkOnWater;
            }
            // Still water: XOR trick (Baritone's assumeWalkOnWater)
            // Normal: deep water (water above) = walkable floor
            // Jesus mode: surface water (no water above) = walkable, deep water = NOT walkable
            return isWater(aboveBlock) ^ ctx.assumeWalkOnWater;
        }
        // Non-water MAYBE: isFullBlock check (rare - barrier blocks, etc.)
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
     * Simple check without neighbor awareness — used by execution layer.
     */
    public static boolean isFlowing(BlockState state) {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && !fluid.isSource();
    }

    /**
     * Enhanced flowing check with neighbor awareness (Baritone's possiblyFlowing).
     * Even LEVEL==0 (visually still) water is considered flowing if any cardinal
     * neighbor has LEVEL!=0. This prevents bots from entering water that would push them.
     */
    public static boolean isFlowing(CalculationContext ctx, int x, int y, int z, BlockState state) {
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) return false;
        if (!fluid.isSource()) return true; // LEVEL != 0 → definitely flowing
        // Source block but check if neighbors make it flow (Baritone: possiblyFlowing)
        return possiblyFlowing(ctx.get(x + 1, y, z))
                || possiblyFlowing(ctx.get(x - 1, y, z))
                || possiblyFlowing(ctx.get(x, y, z + 1))
                || possiblyFlowing(ctx.get(x, y, z - 1));
    }

    private static boolean possiblyFlowing(BlockState state) {
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
        // Baritone: avoid all liquids (prevents diagonal paths from cutting through water)
        // Note: MovementDiagonal has explicit "!= Blocks.WATER" exceptions for feet-level water
        if (block == Blocks.WATER) return true;
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
     * Phase 3C: calculates actual mining cost using vanilla getDestroyProgress().
     *
     * @param includesFalling if true, account for blocks that might fall when broken
     */
    public static double getMiningDurationTicks(CalculationContext ctx, int x, int y, int z,
                                                 BlockState state, boolean includesFalling) {
        if (canWalkThrough(ctx, x, y, z, state)) {
            return 0;  // Already passable, no mining needed
        }

        // Unbreakable blocks (bedrock, barriers, etc.) — hardness < 0
        BlockPos pos = new BlockPos(x, y, z);
        float hardness = state.getDestroySpeed(ctx.getLevel(), pos);
        if (hardness < 0) {
            return ActionCosts.COST_INF;
        }

        // Don't mine liquid blocks (water/lava would flow out)
        if (isLiquid(state)) {
            return ActionCosts.COST_INF;
        }

        // Calculate actual mining duration using vanilla API
        // getDestroyProgress() accounts for: tool type, enchantments (Efficiency),
        // water penalty, mining fatigue, Haste, etc.
        if (ctx.player != null) {
            float progressPerTick = state.getDestroyProgress(ctx.player, ctx.getLevel(), pos);
            if (progressPerTick <= 0) {
                return ActionCosts.COST_INF;
            }
            return Math.ceil(1.0 / progressPerTick);
        }

        // No player reference — can't calculate, treat as impassable
        return ActionCosts.COST_INF;
    }
}
