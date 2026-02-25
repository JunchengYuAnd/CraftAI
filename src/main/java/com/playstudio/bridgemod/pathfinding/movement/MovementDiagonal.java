package com.playstudio.bridgemod.pathfinding.movement;

import com.playstudio.bridgemod.bot.FakePlayer;
import com.playstudio.bridgemod.pathfinding.moves.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

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

        // Fall detection: if bot has fallen below src Y, the diagonal move is irrecoverable.
        // For diagonal ascend (dest Y > src Y): falling below src means 2+ block gap, impossible to jump.
        // For flat/descend diagonal: falling below src-1 means we've deviated too far.
        if (dest.getY() > src.getY()) {
            // Diagonal ascend: if feet below src, can't recover (would need 2-block jump)
            if (feet.getY() < src.getY()) {
                return MovementStatus.UNREACHABLE;
            }
        } else {
            // Flat or descend diagonal: allow 1 block below src, but not more
            if (feet.getY() < src.getY() - 1) {
                return MovementStatus.UNREACHABLE;
            }
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

        if (dest.getY() > src.getY()) {
            // === Diagonal ascend execution ===
            // Don't sprint (reduces residual momentum issues for consecutive diagonal ascends)
            bot.setSprinting(false);

            // Check which intermediate paths are clear.
            // Two cardinal intermediates exist: interA (same X as src) and interB (same X as dest).
            // The cost function requires at least one to be fully passable (3-level clearance).
            // If one is blocked, the bot must approach via the clear side first, then jump to dest.
            BlockPos interA = new BlockPos(src.getX(), src.getY(), dest.getZ());
            BlockPos interB = new BlockPos(dest.getX(), src.getY(), src.getZ());
            boolean clearA = canWalkThroughRuntime(interA) && canWalkThroughRuntime(interA.above());
            boolean clearB = canWalkThroughRuntime(interB) && canWalkThroughRuntime(interB.above());

            // Phase 1: If one side is blocked, walk toward the clear intermediate first.
            // Once close enough, fall through to phase 2 (jump toward dest).
            if (!clearA && clearB) {
                double dx = (interB.getX() + 0.5) - bot.getX();
                double dz = (interB.getZ() + 0.5) - bot.getZ();
                if (dx * dx + dz * dz > 0.4 * 0.4) {
                    moveTowards(interB);
                    return MovementStatus.RUNNING;
                }
            } else if (!clearB && clearA) {
                double dx = (interA.getX() + 0.5) - bot.getX();
                double dz = (interA.getZ() + 0.5) - bot.getZ();
                if (dx * dx + dz * dz > 0.4 * 0.4) {
                    moveTowards(interA);
                    return MovementStatus.RUNNING;
                }
            }

            // Phase 2: Both sides clear or past the detour — move to dest and jump.
            moveTowards(dest);

            if (playerFeet().getY() < dest.getY()) {
                Vec3 vel = bot.getDeltaMovement();
                double dx = (dest.getX() + 0.5) - bot.getX();
                double dz = (dest.getZ() + 0.5) - bot.getZ();
                double dot = vel.x * dx + vel.z * dz;
                double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
                // Jump when velocity is aligned with dest direction, or when hitting the block
                if ((dot > 0 && speed > 0.08) || bot.horizontalCollision) {
                    bot.setMovementInput(1.0f, 0.0f, true);
                }
            }
        } else {
            // === Flat or descend diagonal ===
            bot.setSprinting(canSprintDiagonal());
            moveTowards(dest);
        }

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
