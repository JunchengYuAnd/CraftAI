package com.playstudio.bridgemod.pathfinding.movement;

import com.playstudio.bridgemod.bot.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;

/**
 * Execution logic for jumping straight up and placing a block under feet.
 * This is the "pillar up" technique: same XZ, Y+1.
 *
 * Process:
 * 1. Mine head obstacle at y+2 if needed (handled by base class PREPPING phase)
 * 2. Jump straight up (no horizontal movement)
 * 3. At peak of jump, place block at src (feet position) against src.below() face UP
 * 4. Land on the new block → feet now at dest (src.above())
 *
 * Key constraints:
 * - Must be airborne and above src.Y + 1.0 before placing (entity collision check)
 * - No horizontal movement to avoid drifting off
 * - Block is placed at src position (where feet were), not at dest
 */
public class MovementPillar extends Movement {

    private boolean blockPlaced = false;

    public MovementPillar(FakePlayer bot, BlockPos src, BlockPos dest) {
        super(bot, src, dest);
    }

    @Override
    protected MovementStatus updateState() {
        // First call: detect blocks to mine at y+2 (new head space)
        if (positionsToMine == null) {
            ArrayList<BlockPos> toMine = new ArrayList<>();
            BlockPos newHead = src.above(2);
            if (!canWalkThroughRuntime(newHead)) toMine.add(newHead);
            positionsToMine = toMine.toArray(new BlockPos[0]);

            if (positionsToMine.length > 0) {
                return MovementStatus.RUNNING;
            }
        }

        // CRITICAL: Only check success AFTER block has been placed.
        // During the jump, the bot passes through dest's Y level on the way UP,
        // which would trigger a premature SUCCESS before the block is placed.
        // Without the block, the next pillar movement has no ground → UNREACHABLE.
        if (blockPlaced && playerFeet().equals(dest)) {
            return MovementStatus.SUCCESS;
        }
        if (blockPlaced && bot.isInWater()
                && playerFeet().getX() == dest.getX()
                && playerFeet().getZ() == dest.getZ()
                && Math.abs(bot.getY() - dest.getY()) < 0.8) {
            return MovementStatus.SUCCESS;
        }

        // Fallen below source — unreachable (but tolerate if block not placed yet and still jumping)
        if (blockPlaced && playerFeet().getY() < src.getY()) {
            if (!bot.isInWater() || bot.getY() < src.getY() - 0.8) {
                return MovementStatus.UNREACHABLE;
            }
        }

        // Must have ground to place against
        if (!canWalkOnRuntime(src.below())) {
            return MovementStatus.UNREACHABLE;
        }

        if (!blockPlaced) {
            // Phase 1: Jump and place block

            if (bot.onGround()) {
                // On ground — initiate jump (no horizontal movement)
                bot.setMovementInput(0.0f, 0.0f, true);
                return MovementStatus.RUNNING;
            }

            // Airborne — check if high enough to place block without entity collision.
            // Player hitbox bottom is at bot.getY(). Block at src occupies Y to Y+1.
            // Need feet above the top of the placed block (src.getY() + 1.0).
            if (bot.getY() >= src.getY() + 1.0) {
                if (!bot.hasThrowawayBlock()) return MovementStatus.UNREACHABLE;
                bot.equipThrowaway();
                boolean placed = bot.placeBlock(src.below(), Direction.UP);
                if (placed) {
                    blockPlaced = true;
                    bot.clearMovementInput();
                }
                // If placement failed, keep trying next tick (might need to rise a bit more)
            }
            return MovementStatus.RUNNING;
        }

        // Phase 2: Block placed, wait to land on it
        // No horizontal movement — just wait for gravity
        bot.clearMovementInput();
        return MovementStatus.RUNNING;
    }

    @Override
    public void reset() {
        super.reset();
        blockPlaced = false;
    }
}
