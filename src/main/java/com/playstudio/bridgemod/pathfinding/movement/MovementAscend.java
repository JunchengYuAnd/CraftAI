package com.playstudio.bridgemod.pathfinding.movement;

import com.playstudio.bridgemod.bot.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

/**
 * Execution logic for jumping up 1 block.
 * Ported from Baritone's MovementAscend.updateState().
 *
 * Key behaviors from Baritone:
 * - UNREACHABLE if fallen below src
 * - SUCCESS when feet at dest
 * - Precise jump timing: only jump when close enough to dest horizontally
 *   (flatDistToNext <= 1.2, sideDist <= 0.2, lateralMotion <= 0.1)
 * - No jump needed for bottom slab step-up (within player stepHeight)
 * - No jump if already at src.up() (already jumped, just walk forward)
 * - headBonkClear: jump immediately if overhead is clear in all 4 directions
 * - Phase 3C: pillar-ascend (place block at dest.below when no ground)
 */
public class MovementAscend extends Movement {

    public MovementAscend(FakePlayer bot, BlockPos src, BlockPos dest) {
        super(bot, src, dest);
    }

    @Override
    protected MovementStatus updateState() {
        // Phase 3C: detect obstacle blocks to mine AND pillar blocks to place
        if (positionsToMine == null) {
            ArrayList<BlockPos> toMine = new ArrayList<>();
            BlockPos srcUp2 = src.above(2);
            BlockPos destHead = dest.above();
            if (!canWalkThroughRuntime(srcUp2)) toMine.add(srcUp2);
            if (!canWalkThroughRuntime(dest)) toMine.add(dest);
            if (!canWalkThroughRuntime(destHead)) toMine.add(destHead);
            positionsToMine = toMine.toArray(new BlockPos[0]);

            // Pillar detection: no block to jump onto at dest.below
            BlockPos destBelow = dest.below();
            if (!canWalkOnRuntime(destBelow)) {
                // Place block at destBelow, against the block below it (face UP)
                BlockPos against = destBelow.below();
                if (canWalkOnRuntime(against)) {
                    positionsToPlace = new BlockPos[]{ destBelow };
                    placeAgainstBlocks = new BlockPos[]{ against };
                    placeFaces = new Direction[]{ Direction.UP };
                }
            }

            // If there's pending mining or placement, return RUNNING so the
            // base class update() handles those phases before we continue.
            if (positionsToMine.length > 0
                    || (positionsToPlace != null && positionsToPlace.length > 0)) {
                return MovementStatus.RUNNING;
            }
        }

        // Baritone: if fallen below source, movement is impossible.
        // Water tolerance: bot Y can float slightly below the integer block boundary
        // (e.g. Y=60.99 → playerFeet Y=60 while src Y=61). Use raw Y with tolerance.
        if (playerFeet().getY() < src.getY()) {
            if (!bot.isInWater() || bot.getY() < src.getY() - 0.8) {
                return MovementStatus.UNREACHABLE;
            }
        }

        // Success check — also with water tolerance for Y
        if (playerFeet().equals(dest)) {
            return MovementStatus.SUCCESS;
        }
        if (bot.isInWater()
                && playerFeet().getX() == dest.getX()
                && playerFeet().getZ() == dest.getZ()
                && Math.abs(bot.getY() - dest.getY()) < 0.8) {
            return MovementStatus.SUCCESS;
        }

        // Also check dest + opposite direction (Baritone: dest.add(getDirection().down()))
        // This handles the case where the bot slightly overshot horizontally
        BlockPos direction = new BlockPos(dest.getX() - src.getX(), 0, dest.getZ() - src.getZ());
        if (playerFeet().equals(dest.offset(direction.getX(), -1, direction.getZ()))) {
            return MovementStatus.SUCCESS;
        }

        // Check if the block we're jumping onto exists
        BlockPos positionToPlace = dest.below(); // the block we land on
        if (!canWalkOnRuntime(positionToPlace)) {
            // Block still doesn't exist after placement phase — unreachable
            return MovementStatus.UNREACHABLE;
        }

        // Set movement direction toward dest
        moveTowards(dest);

        // Bottom slab: if jumping onto a bottom slab from non-slab, no jump needed
        // (player stepHeight = 0.6 > 0.5 slab height)
        if (isBottomSlab(positionToPlace) && !isBottomSlab(src.below())) {
            return MovementStatus.RUNNING;
        }

        // Already at or above dest Y level — just walk forward, no jump needed
        if (playerFeet().getY() >= dest.getY()) {
            return MovementStatus.RUNNING;
        }

        // Always jump when ascending. Baritone's precise timing (flatDistToNext,
        // sideDist, lateralMotion, headBonkClear) is optimized for real client players.
        // FakePlayer's server-side physics has subtle differences that cause those
        // conditions to fail intermittently → bot walks into wall without jumping → stuck.
        // Simplified approach: always set jump=true. When onGround()=false (already
        // airborne), jumpFromGround() is a no-op, so this is safe.
        bot.setMovementInput(1.0f, 0.0f, true);
        return MovementStatus.RUNNING;
    }

    /**
     * Check if the overhead space is clear in all 4 horizontal directions from src.up(2).
     * Ported from Baritone's MovementAscend.headBonkClear().
     * If clear, the bot can safely jump early without hitting its head.
     */
    private boolean headBonkClear() {
        BlockPos startUp = src.above(2);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (!canWalkThroughRuntime(startUp.relative(dir))) {
                return false;
            }
        }
        return true;
    }
}
