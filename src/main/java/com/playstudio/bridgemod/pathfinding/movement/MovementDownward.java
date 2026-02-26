package com.playstudio.bridgemod.pathfinding.movement;

import com.playstudio.bridgemod.bot.FakePlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;

/**
 * Execution logic for mining the floor and falling straight down.
 * Same XZ, Y decreases by 1+ (depends on what's below the mined floor).
 *
 * Process:
 * 1. Mine the block at src.below() (the floor the bot is standing on)
 *    — handled by base class PREPPING phase via positionsToMine[]
 * 2. After mining, gravity pulls the bot down
 * 3. SUCCESS when feet reach dest position
 *
 * Key constraints:
 * - No horizontal movement (pure vertical descent)
 * - Only mines 1 block (the floor); landing block must already be solid
 * - If bot falls too far below dest, UNREACHABLE (unexpected cave/void)
 */
public class MovementDownward extends Movement {

    public MovementDownward(FakePlayer bot, BlockPos src, BlockPos dest) {
        super(bot, src, dest);
    }

    @Override
    protected MovementStatus updateState() {
        // First call: detect block to mine (the floor at src.below)
        if (positionsToMine == null) {
            ArrayList<BlockPos> toMine = new ArrayList<>();
            BlockPos floor = src.below();
            if (!canWalkThroughRuntime(floor)) {
                toMine.add(floor);
            }
            positionsToMine = toMine.toArray(new BlockPos[0]);

            if (positionsToMine.length > 0) {
                return MovementStatus.RUNNING;
            }
        }

        BlockPos feet = playerFeet();

        // Success: feet at dest
        if (feet.equals(dest)) {
            return MovementStatus.SUCCESS;
        }

        // Water tolerance (bot Y can float slightly off integer boundary)
        if (bot.isInWater()
                && feet.getX() == dest.getX()
                && feet.getZ() == dest.getZ()
                && Math.abs(bot.getY() - dest.getY()) < 0.8) {
            return MovementStatus.SUCCESS;
        }

        // Fallen too far below dest — unexpected deeper fall
        if (bot.getY() < dest.getY() - 2.0) {
            return MovementStatus.UNREACHABLE;
        }

        // Floor mined, wait for gravity — no horizontal movement needed
        bot.clearMovementInput();
        return MovementStatus.RUNNING;
    }
}
