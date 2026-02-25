package com.playstudio.bridgemod.pathfinding.movement;

import com.playstudio.bridgemod.bot.FakePlayer;
import com.playstudio.bridgemod.pathfinding.moves.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * Execution logic for walking off an edge and falling 1+ blocks.
 * Ported from Baritone's MovementDescend.updateState().
 *
 * Key behaviors from Baritone:
 * - fakeDest = dest * 2 - src: an overshoot point beyond dest in the movement direction.
 *   Moving toward fakeDest creates momentum to reliably walk off the edge.
 * - First 20 ticks while close to start: move toward fakeDest (build momentum)
 * - After 20 ticks or far from start: move toward dest (center on landing)
 * - safeMode: if blocks beyond dest are dangerous or there's a wall (next move is ascend),
 *   use a weighted target (17% src, 83% dest) instead of overshooting
 * - SUCCESS when at dest block position AND bot Y close to dest Y
 */
public class MovementDescend extends Movement {

    private int numTicks = 0;

    public MovementDescend(FakePlayer bot, BlockPos src, BlockPos dest) {
        super(bot, src, dest);
    }

    @Override
    public void reset() {
        super.reset();
        numTicks = 0;
    }

    @Override
    protected MovementStatus updateState() {
        BlockPos feet = playerFeet();

        // fakeDest: a point 1 block beyond dest in the movement direction.
        // Moving toward it builds horizontal momentum to walk off the edge.
        BlockPos fakeDest = new BlockPos(
                dest.getX() * 2 - src.getX(),
                dest.getY(),
                dest.getZ() * 2 - src.getZ()
        );

        // Success check (Baritone: at dest or fakeDest, and Y close to dest)
        if ((feet.equals(dest) || feet.equals(fakeDest))
                && (isLiquid(dest) || bot.getY() - dest.getY() < 0.5)) {
            return MovementStatus.SUCCESS;
        }

        // Safe mode: check for dangerous blocks beyond dest
        if (safeMode()) {
            // Weighted target: 17% src, 83% dest (Baritone's safe descend)
            double destX = (src.getX() + 0.5) * 0.17 + (dest.getX() + 0.5) * 0.83;
            double destZ = (src.getZ() + 0.5) * 0.17 + (dest.getZ() + 0.5) * 0.83;
            moveTowards(destX, destZ);
            return MovementStatus.RUNNING;
        }

        // Standard descent with overshoot mechanics
        double diffX = bot.getX() - (dest.getX() + 0.5);
        double diffZ = bot.getZ() - (dest.getZ() + 0.5);
        double distFromDest = Math.sqrt(diffX * diffX + diffZ * diffZ);
        double x = bot.getX() - (src.getX() + 0.5);
        double z = bot.getZ() - (src.getZ() + 0.5);
        double fromStart = Math.sqrt(x * x + z * z);

        if (!feet.equals(dest) || distFromDest > 0.25) {
            if (numTicks++ < 20 && fromStart < 1.25) {
                // Phase 1: move toward fakeDest to build momentum
                moveTowards(fakeDest);
            } else {
                // Phase 2: move toward dest to center on landing
                moveTowards(dest);
            }
        }

        return MovementStatus.RUNNING;
    }

    /**
     * Check if safe mode should be used.
     * Ported from Baritone's MovementDescend.safeMode().
     *
     * Returns true if:
     * - There are dangerous blocks (fire, lava, cactus) beyond dest
     * - There's a wall beyond dest (next move would be ascend) - skipToAscend
     */
    private boolean safeMode() {
        // "into" = 1 block beyond dest in the movement direction
        int dx = dest.getX() - src.getX();
        int dz = dest.getZ() - src.getZ();
        BlockPos into = dest.offset(dx, 0, dz);

        // skipToAscend: solid block at ground level beyond dest, but air above
        // This means the next movement is likely an ascend, so don't overshoot into the wall
        if (skipToAscend(into)) {
            return true;
        }

        // Check for dangerous blocks in the 3-block column beyond dest
        for (int y = 0; y <= 2; y++) {
            Block block = bot.serverLevel().getBlockState(into.above(y)).getBlock();
            if (MovementHelper.avoidWalkingInto(block)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if there's a wall at ground level beyond dest (indicating the next move is an ascend).
     * Ported from Baritone's MovementDescend.skipToAscend().
     */
    private boolean skipToAscend(BlockPos into) {
        return !canWalkThroughRuntime(into)
                && canWalkThroughRuntime(into.above())
                && canWalkThroughRuntime(into.above(2));
    }
}
