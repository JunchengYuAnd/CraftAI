package com.playstudio.bridgemod.pathfinding.movement;

import com.playstudio.bridgemod.bot.FakePlayer;
import net.minecraft.core.BlockPos;

/**
 * Execution logic for sprint-jumping across a 2-3 block gap (flat, same Y).
 *
 * Key behaviors:
 * - Sprint toward destination, building momentum before jumping
 * - Jump when near the gap edge (not from standing) to ensure enough distance
 * - SUCCESS when feet at dest or 1-block overshoot in movement direction
 * - UNREACHABLE if fallen below source Y or timeout (40 ticks)
 * - No mining or block placement (can't interact mid-air)
 *
 * Sprint-jump physics:
 * - From standing (0 momentum): covers ~2.7 blocks → enough for 2-block gap only
 * - With 2-tick runway: covers ~3.4 blocks → enough for 3-block gap
 * - The sprint jump boost (+0.2 velocity) is critical and only applies on the jump tick
 */
public class MovementParkour extends Movement {

    private int tickCount = 0;
    private boolean jumped = false;

    public MovementParkour(FakePlayer bot, BlockPos src, BlockPos dest) {
        super(bot, src, dest);
    }

    @Override
    protected MovementStatus updateState() {
        tickCount++;
        BlockPos feet = playerFeet();

        // Success: at destination
        if (feet.equals(dest)) {
            return MovementStatus.SUCCESS;
        }

        // Overshoot tolerance: 1 block past dest in movement direction
        int dirX = Integer.signum(dest.getX() - src.getX());
        int dirZ = Integer.signum(dest.getZ() - src.getZ());
        BlockPos overshoot = dest.offset(dirX, 0, dirZ);
        if (feet.equals(overshoot)) {
            return MovementStatus.SUCCESS;
        }

        // Fallen below source — failed the jump
        if (bot.getY() < src.getY() - 1.0) {
            return MovementStatus.UNREACHABLE;
        }

        // Timeout: normal parkour completes in ~20 ticks
        if (tickCount > 40) {
            return MovementStatus.UNREACHABLE;
        }

        // Sprint toward destination
        moveTowards(dest);
        bot.setSprinting(true);

        // Jump timing: need sprint momentum before jumping.
        // Calculate how far the bot has moved from src center toward the gap edge.
        // src edge (gap start) is 0.5 blocks from src center in the movement direction.
        if (bot.onGround()) {
            if (!jumped) {
                // How far toward the gap edge have we progressed?
                double progress;
                if (dirX != 0) {
                    progress = (bot.getX() - (src.getX() + 0.5)) * dirX;
                } else {
                    progress = (bot.getZ() - (src.getZ() + 0.5)) * dirZ;
                }
                // Jump when past src center (progress >= 0): ensures 2+ ticks of sprint runway.
                // At this point the bot has sprint velocity and is near the gap edge.
                // Also jump if tick > 3 as fallback (bot might start off-center).
                if (progress >= 0.0 || tickCount > 3) {
                    bot.setMovementInput(1.0f, 0.0f, true);  // forward + jump
                    jumped = true;
                }
            } else {
                // Already jumped once but back on ground (landed short or on edge).
                // Jump again to try to reach dest.
                bot.setMovementInput(1.0f, 0.0f, true);
            }
        }

        return MovementStatus.RUNNING;
    }

    @Override
    public void reset() {
        super.reset();
        tickCount = 0;
        jumped = false;
    }
}
