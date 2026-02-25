package com.playstudio.bridgemod.pathfinding.movement;

import com.playstudio.bridgemod.bot.FakePlayer;
import com.playstudio.bridgemod.pathfinding.moves.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;

/**
 * Execution logic for flat 1-block cardinal movement.
 * Ported from Baritone's MovementTraverse.updateState().
 *
 * Key behaviors:
 * - SUCCESS when feet at dest (or overshoot by 1-2 blocks in direction)
 * - Y correction: jump if below dest Y
 * - Sprint when path ahead is clear
 * - Handles ladder/vine at source
 */
public class MovementTraverse extends Movement {

    public MovementTraverse(FakePlayer bot, BlockPos src, BlockPos dest) {
        super(bot, src, dest);
    }

    @Override
    protected MovementStatus updateState() {
        BlockPos feet = playerFeet();

        // Success: at destination
        if (feet.equals(dest)) {
            return MovementStatus.SUCCESS;
        }

        // Overshoot check (Baritone's overshootTraverse):
        // If the bot walked 1-2 blocks past dest in the movement direction, still count as success.
        BlockPos direction = dest.subtract(src);
        BlockPos overshoot1 = dest.offset(direction);
        BlockPos overshoot2 = overshoot1.offset(direction);
        if (feet.equals(overshoot1) || feet.equals(overshoot2)) {
            return MovementStatus.SUCCESS;
        }

        // Y correction (Baritone: "Wrong Y coordinate")
        Block srcDownBlock = bot.serverLevel().getBlockState(src.below()).getBlock();
        boolean ladder = srcDownBlock instanceof LadderBlock || srcDownBlock instanceof VineBlock;
        if (feet.getY() != dest.getY() && !ladder) {
            if (feet.getY() < dest.getY()) {
                // Need to jump up (edge case: traverse path but terrain changed)
                moveTowards(dest);
                bot.setMovementInput(1.0f, 0.0f, true);
                return MovementStatus.RUNNING;
            }
            // Above dest Y - keep walking, gravity will bring us down
            moveTowards(dest);
            return MovementStatus.RUNNING;
        }

        // Check ground at destination (Baritone: isTheBridgeBlockThere)
        boolean bridgeBlockThere = canWalkOnRuntime(dest.below()) || ladder;
        if (!bridgeBlockThere) {
            // No ground at dest - in Phase 3B we can't bridge, mark unreachable
            return MovementStatus.UNREACHABLE;
        }

        // Ladder/vine: if currently on ladder and above ground, wait for descent
        if (ladder && bot.getY() > src.getY() + 0.1 && !bot.onGround()) {
            return MovementStatus.RUNNING;
        }

        // Sprint logic (Baritone-style):
        // Sprint if the blocks beyond dest are safe to walk into.
        BlockPos into = dest.offset(direction);
        Block intoBelow = bot.serverLevel().getBlockState(into).getBlock();
        Block intoAbove = bot.serverLevel().getBlockState(into.above()).getBlock();
        boolean canSprint = !isLiquid(feet)
                && (!MovementHelper.avoidWalkingInto(intoBelow) || MovementHelper.isWater(intoBelow))
                && !MovementHelper.avoidWalkingInto(intoAbove);
        bot.setSprinting(canSprint);

        moveTowards(dest);
        return MovementStatus.RUNNING;
    }
}
