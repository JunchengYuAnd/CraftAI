package com.playstudio.bridgemod.pathfinding.movement;

import com.playstudio.bridgemod.bot.FakePlayer;
import com.playstudio.bridgemod.pathfinding.moves.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.state.BlockState;

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
 * - No block breaking/placing (Phase 3B): PREPPING phase is skipped
 * - Uses ServerLevel directly for block checks during execution
 */
public abstract class Movement {

    protected final FakePlayer bot;
    protected final BlockPos src;
    protected final BlockPos dest;
    protected MovementStatus status = MovementStatus.RUNNING;

    protected Movement(FakePlayer bot, BlockPos src, BlockPos dest) {
        this.bot = bot;
        this.src = src;
        this.dest = dest;
    }

    /**
     * Execute one tick of this movement.
     * Called by PathExecutor each server tick.
     * Ported from Baritone's Movement.update().
     */
    public MovementStatus update() {
        if (status.isComplete()) {
            return status;
        }
        status = updateState();
        return status;
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
        if (state.getBlock() == Blocks.WATER) {
            if (MovementHelper.isFlowing(state)) return false;
            BlockState above = bot.serverLevel().getBlockState(pos.above());
            if (above.getBlock() == Blocks.WATER) return false; // deep water is NOT passable
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
        if (state.getBlock() == Blocks.WATER) {
            Block aboveBlock = bot.serverLevel().getBlockState(pos.above()).getBlock();
            // Lily pad / carpet on water: always walkable
            if (aboveBlock instanceof WaterlilyBlock || aboveBlock instanceof CarpetBlock) return true;
            return aboveBlock == Blocks.WATER; // deep water column → walkable
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
