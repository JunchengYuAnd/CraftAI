package com.playstudio.bridgemod.pathfinding;

import com.playstudio.bridgemod.BridgeMod;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Pre-computed block state flags for fast pathfinding lookups.
 * Ported from Baritone's PrecomputedData.
 *
 * Replaces instanceof chains in MovementHelper with O(1) array lookups.
 * Each BlockState gets a bit-flag encoding of canWalkThrough and canWalkOn results:
 *   - YES: result is known, return immediately
 *   - NO: result is known, return immediately
 *   - MAYBE: need position-dependent check (collision shape)
 *
 * ~85% of block states resolve to YES or NO, eliminating 10+ instanceof checks per call.
 * The remaining ~15% (MAYBE) fall through to the position-dependent slow path.
 */
public class PrecomputedData {

    // canWalkThrough: bits 0-1
    public static final int WTH_MAYBE = 0;   // need position-dependent check
    public static final int WTH_YES   = 1;   // definitely passable
    public static final int WTH_NO    = 2;   // definitely not passable
    public static final int WTH_MASK  = 3;

    // canWalkOn: bits 2-3
    public static final int WON_MAYBE = 0;        // need position-dependent check
    public static final int WON_YES   = 1 << 2;   // definitely walkable
    public static final int WON_NO    = 2 << 2;   // definitely not walkable
    public static final int WON_MASK  = 3 << 2;

    private static volatile PrecomputedData instance;

    private final int[] flags;

    private PrecomputedData() {
        int size = Block.BLOCK_STATE_REGISTRY.size();
        flags = new int[size];
        int yesWth = 0, noWth = 0, maybeWth = 0;
        int yesWon = 0, noWon = 0, maybeWon = 0;
        for (int i = 0; i < size; i++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(i);
            if (state != null) {
                flags[i] = computeFlags(state);
                int wth = flags[i] & WTH_MASK;
                if (wth == WTH_YES) yesWth++;
                else if (wth == WTH_NO) noWth++;
                else maybeWth++;
                int won = flags[i] & WON_MASK;
                if (won == WON_YES) yesWon++;
                else if (won == WON_NO) noWon++;
                else maybeWon++;
            }
        }
        BridgeMod.LOGGER.info("PrecomputedData: {} states, walkThrough(Y={} N={} M={}), walkOn(Y={} N={} M={})",
                size, yesWth, noWth, maybeWth, yesWon, noWon, maybeWon);
    }

    public static PrecomputedData getInstance() {
        if (instance == null) {
            synchronized (PrecomputedData.class) {
                if (instance == null) {
                    instance = new PrecomputedData();
                }
            }
        }
        return instance;
    }

    /**
     * Get the pre-computed flags for a block state.
     * O(1) array lookup by block state registry ID.
     */
    public int getFlags(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id >= 0 && id < flags.length) {
            return flags[id];
        }
        return WTH_MAYBE | WON_MAYBE; // Unknown state, fall through to position check
    }

    private static int computeFlags(BlockState state) {
        return computeCanWalkThrough(state) | computeCanWalkOn(state);
    }

    // ==================== canWalkThrough ====================
    // Mirrors MovementHelper.canWalkThrough logic, but without position-dependent checks.

    private static int computeCanWalkThrough(BlockState state) {
        Block block = state.getBlock();

        // Air: always passable
        if (state.isAir()) return WTH_YES;

        // Explicitly blocked blocks (Baritone: return NO)
        if (block instanceof FireBlock || block instanceof BaseFireBlock) return WTH_NO;
        if (block instanceof TripWireBlock) return WTH_NO;
        if (block instanceof EndPortalBlock) return WTH_NO;
        if (block instanceof SkullBlock || block instanceof WallSkullBlock) return WTH_NO;
        if (block instanceof CactusBlock) return WTH_NO;
        if (block instanceof TrapDoorBlock) return WTH_NO;

        // Doors: passable (can open), except iron door
        if (block instanceof DoorBlock) {
            return block != Blocks.IRON_DOOR ? WTH_YES : WTH_NO;
        }

        // Fence gates: passable (can open)
        if (block instanceof FenceGateBlock) return WTH_YES;

        // Liquids
        FluidState fluid = state.getFluidState();
        if (fluid.is(Fluids.LAVA) || fluid.is(Fluids.FLOWING_LAVA)) return WTH_NO;
        // Water: needs position-dependent check (only still, surface, 1-block-deep is passable)
        if (fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER)) return WTH_MAYBE;

        // Snow layers: depends on layer count (per-state, can precompute)
        if (block instanceof SnowLayerBlock) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            return layers < 3 ? WTH_YES : WTH_NO;
        }

        // Carpet: always passable
        if (block instanceof CarpetBlock) return WTH_YES;

        // Fast path for common solid blocks: canOcclude = opaque full cube → NOT passable
        if (state.canOcclude()) return WTH_NO;

        // Remaining blocks: need position-dependent collision shape check
        // Covers: flowers, tall grass, torches, signs, fences, walls, glass panes, bamboo, etc.
        return WTH_MAYBE;
    }

    // ==================== canWalkOn ====================
    // Mirrors MovementHelper.canWalkOn logic, but without position-dependent checks.

    private static int computeCanWalkOn(BlockState state) {
        Block block = state.getBlock();

        // Air: can't stand on
        if (state.isAir()) return WON_NO;

        // Magma: avoid
        if (block == Blocks.MAGMA_BLOCK) return WON_NO;

        // Full solid cube (canOcclude covers stone, dirt, logs, ores, wool, etc.)
        if (state.canOcclude()) return WON_YES;

        // Specific walkable non-full blocks (Baritone explicit list):
        if (block instanceof LadderBlock || block instanceof VineBlock) return WON_YES;
        if (block instanceof FarmBlock || block instanceof DirtPathBlock) return WON_YES;
        if (block instanceof ChestBlock || block instanceof EnderChestBlock) return WON_YES;
        if (block instanceof ShulkerBoxBlock) return WON_YES;
        if (block instanceof GlassBlock || block instanceof StainedGlassBlock) return WON_YES;
        if (block instanceof StairBlock) return WON_YES;
        if (block instanceof SlabBlock) {
            SlabType type = state.getValue(SlabBlock.TYPE);
            return (type == SlabType.DOUBLE || type == SlabType.BOTTOM || type == SlabType.TOP)
                    ? WON_YES : WON_NO;
        }
        if (block instanceof BedBlock) return WON_YES;
        if (block instanceof CampfireBlock) return WON_YES;
        // Lily pad: always walkable (Baritone explicit)
        if (block instanceof WaterlilyBlock) return WON_YES;

        // Water: needs position-dependent check (deep water column = walkable floor)
        FluidState fluid = state.getFluidState();
        if (fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER)) return WON_MAYBE;

        // Remaining: might be full block via isCollisionShapeFullBlock (rare in vanilla).
        // Covers: barrier block, some modded blocks.
        // Use MAYBE to fall through to position-dependent isFullBlock check.
        return WON_MAYBE;
    }
}
