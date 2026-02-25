package com.playstudio.bridgemod.pathfinding.movement;

import com.playstudio.bridgemod.bot.FakePlayer;
import com.playstudio.bridgemod.pathfinding.moves.MovementHelper;
import net.minecraft.core.BlockPos;

/**
 * Execution logic for diagonal movement (1 block diagonal, possibly +/-1 Y).
 * Ported from Baritone's MovementDiagonal.updateState().
 *
 * Key behaviors from Baritone:
 * - SUCCESS when feet at dest
 * - UNREACHABLE if not in a valid position
 * - Jump if ascending diagonally and collided horizontally
 * - Sprint if all 4 intermediate positions are passable
 * - Simple: moveTowards(dest)
 */
public class MovementDiagonal extends Movement {

    public MovementDiagonal(FakePlayer bot, BlockPos src, BlockPos dest) {
        super(bot, src, dest);
    }

    @Override
    protected MovementStatus updateState() {
        BlockPos feet = playerFeet();

        // Success check
        if (feet.equals(dest)) {
            return MovementStatus.SUCCESS;
        }

        // Valid position check (Baritone: playerInValidPosition)
        // For diagonal, valid positions are: src, dest, and the two intermediate blocks
        BlockPos diagA = new BlockPos(src.getX(), src.getY(), dest.getZ());
        BlockPos diagB = new BlockPos(dest.getX(), src.getY(), src.getZ());
        if (!feet.equals(src) && !feet.equals(dest)
                && !feet.equals(diagA) && !feet.equals(diagB)
                && !feet.equals(diagA.below()) && !feet.equals(diagB.below())
                && !feet.equals(diagA.above()) && !feet.equals(diagB.above())
                && !feet.equals(src.above()) && !feet.equals(dest.above())) {
            // Check if in liquid at src (Baritone exception)
            if (!isLiquid(src) || !feet.equals(src.above())) {
                return MovementStatus.UNREACHABLE;
            }
        }

        // Jump for diagonal ascend (Baritone: if dest above src and player low + collided)
        if (dest.getY() > src.getY()
                && bot.getY() < src.getY() + 0.1
                && bot.horizontalCollision) {
            moveTowards(dest);
            bot.setMovementInput(1.0f, 0.0f, true);
            return MovementStatus.RUNNING;
        }

        // Sprint check (Baritone: check all 4 intermediate positions are passable)
        bot.setSprinting(canSprintDiagonal());

        moveTowards(dest);
        return MovementStatus.RUNNING;
    }

    /**
     * Check if we can sprint diagonally.
     * Ported from Baritone's MovementDiagonal.sprint().
     * Checks that all 4 intermediate positions (2 cardinal neighbors at foot+head level)
     * are passable, preventing sprinting into a wall corner.
     */
    private boolean canSprintDiagonal() {
        if (isLiquid(playerFeet())) {
            return false;
        }

        // The 4 intermediate positions: two cardinal neighbors at foot and head level
        BlockPos interA = new BlockPos(src.getX(), src.getY(), dest.getZ());
        BlockPos interB = new BlockPos(dest.getX(), src.getY(), src.getZ());

        return canWalkThroughRuntime(interA)
                && canWalkThroughRuntime(interA.above())
                && canWalkThroughRuntime(interB)
                && canWalkThroughRuntime(interB.above());
    }
}
