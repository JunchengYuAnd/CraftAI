package com.playstudio.bridgemod.pathfinding.movement;

import com.playstudio.bridgemod.bot.FakePlayer;
import com.playstudio.bridgemod.pathfinding.moves.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for movement execution.
 * Ported from Baritone's Movement class, adapted for FakePlayer.
 *
 * Each subclass implements updateState() with movement-specific execution logic
 * (jump timing, overshoot, sprint conditions, etc.) instead of a generic
 * "walk toward node center" approach.
 *
 * Key differences from Baritone:
 * - No InputOverrideHandler: we call bot.setMovementInput() directly
 * - No LookBehavior: we call bot.setYRot()/setXRot() directly
 * - Uses ServerLevel directly for block checks during execution
 *
 * Execution phases (Phase 3C):
 * 1. PREPPING — mine obstacle blocks (positionsToMine[])
 * 2. PLACING — place blocks for bridge/pillar (positionsToPlace[])
 * 3. EXECUTING — movement-specific logic (updateState())
 */
public abstract class Movement {

    protected final FakePlayer bot;
    protected final BlockPos src;
    protected final BlockPos dest;
    protected MovementStatus status = MovementStatus.RUNNING;

    // Phase 3C: mining during pathfinding
    protected BlockPos[] positionsToMine;  // blocks to mine before moving (filled by subclass)
    protected int miningIndex = 0;
    protected boolean isMining = false;

    // Phase 3C: block placement during pathfinding (bridge/pillar)
    protected BlockPos[] positionsToPlace;    // blocks to place (filled by subclass)
    protected BlockPos[] placeAgainstBlocks;  // corresponding "against" blocks for placement
    protected Direction[] placeFaces;         // corresponding face directions
    protected int placingIndex = 0;

    protected Movement(FakePlayer bot, BlockPos src, BlockPos dest) {
        this.bot = bot;
        this.src = src;
        this.dest = dest;
    }

    /**
     * Execute one tick of this movement.
     * Called by PathExecutor each server tick.
     *
     * Execution order: mine obstacles → place blocks → move.
     * Subclasses fill positionsToMine[] and positionsToPlace[] in their updateState() first call.
     */
    public MovementStatus update() {
        if (status.isComplete()) {
            return status;
        }

        // PREPPING: mine obstacle blocks before moving
        if (positionsToMine != null && miningIndex < positionsToMine.length) {
            return tickMining();
        }

        // PLACING: place blocks for bridge/pillar
        if (positionsToPlace != null && placingIndex < positionsToPlace.length) {
            return tickPlacing();
        }

        // EXECUTING: normal movement
        status = updateState();
        return status;
    }

    /**
     * Mine the next block in positionsToMine[].
     * Uses FakePlayer.startDigging() — the same progressive mining as bot_dig.
     */
    private MovementStatus tickMining() {
        BlockPos target = positionsToMine[miningIndex];
        BlockState state = bot.serverLevel().getBlockState(target);

        // Already passable → skip to next
        if (state.isAir() || state.getCollisionShape(bot.serverLevel(), target).isEmpty()) {
            miningIndex++;
            isMining = false;
            return MovementStatus.RUNNING;
        }

        // Not yet digging → start
        if (!bot.isDigging()) {
            bot.clearMovementInput();
            bot.startDigging(target, null, (success, reason) -> {
                isMining = false;
                miningIndex++;
            });
            isMining = true;
        }

        // Wait for digging to complete (tickDigging runs in FakePlayer.tick())
        return MovementStatus.RUNNING;
    }

    /**
     * Place the next block in positionsToPlace[].
     * Uses FakePlayer.placeBlock() — instant placement (unlike progressive mining).
     */
    private MovementStatus tickPlacing() {
        BlockPos target = positionsToPlace[placingIndex];

        // Already has a solid block → skip
        if (canWalkOnRuntime(target)) {
            placingIndex++;
            return MovementStatus.RUNNING;
        }

        // Equip a throwaway block
        if (!bot.hasThrowawayBlock()) {
            return MovementStatus.UNREACHABLE;
        }
        bot.equipThrowaway();

        // Place block
        boolean placed = bot.placeBlock(placeAgainstBlocks[placingIndex], placeFaces[placingIndex]);
        if (placed) {
            placingIndex++;
        } else {
            return MovementStatus.UNREACHABLE;
        }
        return MovementStatus.RUNNING;
    }

    /**
     * Movement-specific execution logic.
     * Each subclass implements this with its own state machine.
     */
    protected abstract MovementStatus updateState();

    /**
     * Reset this movement for re-execution (e.g., after path snapping).
     * Ported from Baritone's Movement.reset().
     */
    public void reset() {
        status = MovementStatus.RUNNING;
        positionsToMine = null;
        miningIndex = 0;
        isMining = false;
        positionsToPlace = null;
        placeAgainstBlocks = null;
        placeFaces = null;
        placingIndex = 0;
    }

    // ==================== Helpers (adapted from Baritone's MovementHelper) ====================

    /**
     * Set the bot's yaw to face the target block center and walk forward.
     * Ported from Baritone's MovementHelper.moveTowards(ctx, state, pos).
     */
    protected void moveTowards(BlockPos target) {
        double dx = (target.getX() + 0.5) - bot.getX();
        double dz = (target.getZ() + 0.5) - bot.getZ();
        float yaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        bot.setMovementInput(1.0f, 0.0f, false);
    }

    /**
     * Move toward an arbitrary XZ position (for overshoot targets).
     */
    protected void moveTowards(double targetX, double targetZ) {
        double dx = targetX - bot.getX();
        double dz = targetZ - bot.getZ();
        float yaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        bot.setMovementInput(1.0f, 0.0f, false);
    }

    /**
     * Get the bot's current feet block position (floored).
     * Equivalent to Baritone's ctx.playerFeet().
     */
    protected BlockPos playerFeet() {
        return BlockPos.containing(bot.getX(), bot.getY(), bot.getZ());
    }

    /**
     * Check if a block position is passable (no collision shape).
     * Runtime equivalent of MovementHelper.canWalkThrough, using ServerLevel directly.
     * Must match pathfinding layer's water semantics.
     */
    protected boolean canWalkThroughRuntime(BlockPos pos) {
        BlockState state = bot.serverLevel().getBlockState(pos);
        if (state.isAir()) return true;
        // Water: only surface still water is passable (matches pathfinding layer)
        // Uses FluidState to match waterlogged blocks (seagrass, kelp, etc.)
        if (MovementHelper.isWater(state)) {
            // Waterlogged blocks with collision shapes (fences, etc.) are NOT passable
            if (state.getBlock() != Blocks.WATER
                    && !state.getCollisionShape(bot.serverLevel(), pos).isEmpty()) {
                return false;
            }
            if (MovementHelper.isFlowing(state)) return false;
            BlockState above = bot.serverLevel().getBlockState(pos.above());
            if (MovementHelper.isWater(above)) return false; // deep water is NOT passable
            if (above.getBlock() instanceof WaterlilyBlock) return false; // lily pad covers water
            return true;
        }
        return state.getCollisionShape(bot.serverLevel(), pos).isEmpty();
    }

    /**
     * Check if a block position has a solid collision shape (can stand on).
     * Runtime equivalent of MovementHelper.canWalkOn, using ServerLevel directly.
     * Must match pathfinding layer's water semantics.
     */
    protected boolean canWalkOnRuntime(BlockPos pos) {
        BlockState state = bot.serverLevel().getBlockState(pos);
        if (state.isAir()) return false;
        // Water: deep water (water above) acts as walkable floor (matches pathfinding layer)
        // Uses FluidState to match waterlogged blocks (seagrass, kelp, etc.)
        if (MovementHelper.isWater(state)) {
            BlockState aboveState = bot.serverLevel().getBlockState(pos.above());
            Block aboveBlock = aboveState.getBlock();
            // Lily pad / carpet on water: always walkable
            if (aboveBlock instanceof WaterlilyBlock || aboveBlock instanceof CarpetBlock) return true;
            return MovementHelper.isWater(aboveState); // deep water column → walkable
        }
        return !state.getCollisionShape(bot.serverLevel(), pos).isEmpty();
    }

    /**
     * Check if the block at pos is a bottom slab.
     */
    protected boolean isBottomSlab(BlockPos pos) {
        return MovementHelper.isBottomSlab(bot.serverLevel().getBlockState(pos));
    }

    /**
     * Check if the block at pos is liquid.
     */
    protected boolean isLiquid(BlockPos pos) {
        return MovementHelper.isLiquid(bot.serverLevel().getBlockState(pos));
    }

    public BlockPos getSrc() {
        return src;
    }

    public BlockPos getDest() {
        return dest;
    }

    public MovementStatus getStatus() {
        return status;
    }
}
