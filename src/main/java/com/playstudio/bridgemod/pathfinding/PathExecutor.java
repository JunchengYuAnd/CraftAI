package com.playstudio.bridgemod.pathfinding;

import com.playstudio.bridgemod.BridgeMod;
import com.playstudio.bridgemod.bot.FakePlayer;
import com.playstudio.bridgemod.pathfinding.movement.*;
import com.playstudio.bridgemod.pathfinding.moves.Moves;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Executes a calculated path tick-by-tick by delegating to Movement objects.
 * Ported from Baritone's PathExecutor architecture.
 *
 * Key difference from the old generic "moveTowardNode" approach:
 * Each step in the path has a dedicated Movement object (MovementTraverse, MovementAscend,
 * MovementDescend, MovementDiagonal) with its own execution logic (jump timing, overshoot,
 * sprint conditions, etc.). This eliminates the generic distance-based node-reach check
 * that caused bugs like skipping ascend nodes.
 *
 * Flow per tick:
 * 1. Forward scan: check if bot skipped ahead to a future path node
 * 2. Stuck detection (every 10 ticks)
 * 3. Horizontal deviation check
 * 4. Execute current movement (movement.update())
 * 5. Handle result: SUCCESS → advance, UNREACHABLE → fail, RUNNING → continue
 *
 * From Baritone:
 * - When a movement completes (SUCCESS), immediately try the next movement in the same tick
 *   (loop, not recursion, to avoid stack overflow)
 * - Forward scan skips from pathPosition+3 onward (not +1 or +2, to let current movement finish)
 * - Stuck detection: both velocity-based (no movement for 40 ticks) and per-movement timeout
 */
public class PathExecutor {

    private final FakePlayer bot;
    private final List<PathNode> path;
    private final Movement[] movements;

    private int pathPosition = 0;
    private int ticksOnCurrent = 0;
    private int totalTicks = 0;

    // Stuck detection
    private double lastX, lastY, lastZ;
    private int ticksAway = 0;
    private int stuckTicks = 0;

    // Constants from Baritone's PathExecutor
    private static final double MAX_DIST_FROM_PATH = 2.0;
    private static final double MAX_MAX_DIST_FROM_PATH = 3.0;
    private static final int MAX_TICKS_AWAY = 200;
    private static final int MAX_TICKS_PER_MOVEMENT = 300;  // Phase 3C: allow time for mining (stone ~30t, obsidian ~600t)
    private static final int STUCK_THRESHOLD = 60;  // Phase 3C: mining keeps bot stationary longer
    private static final double MOVE_THRESHOLD = 0.05;

    public enum Status {
        IN_PROGRESS,
        COMPLETED,
        FAILED_STUCK,
        FAILED_DEVIATED
    }

    public PathExecutor(FakePlayer bot, List<PathNode> path) {
        this.bot = bot;
        this.path = path;
        this.lastX = bot.getX();
        this.lastY = bot.getY();
        this.lastZ = bot.getZ();

        // Create Movement objects from consecutive path nodes
        this.movements = new Movement[Math.max(0, path.size() - 1)];
        for (int i = 0; i < movements.length; i++) {
            PathNode from = path.get(i);
            PathNode to = path.get(i + 1);
            movements[i] = createMovement(bot, from, to);
        }
    }

    /**
     * Execute one tick of path following.
     */
    public Status tick() {
        if (movements.length == 0 || pathPosition >= movements.length) {
            return Status.COMPLETED;
        }

        totalTicks++;
        ticksOnCurrent++;

        // 1. Forward scan: check if bot jumped ahead to a future path node.
        // Start from pathPosition+3 (Baritone: skip +1 and +2 to let current movement finish properly).
        BlockPos feet = BlockPos.containing(bot.getX(), bot.getY(), bot.getZ());
        for (int i = pathPosition + 3; i < movements.length; i++) {
            PathNode node = path.get(i + 1); // movement[i] goes to path[i+1]
            if (feet.getX() == node.x && feet.getY() == node.y && feet.getZ() == node.z) {
                BridgeMod.LOGGER.debug("PathExecutor: forward snap from movement {} to {} (bot at path node {})",
                        pathPosition, i, i + 1);
                // Reset skipped movements
                for (int j = pathPosition; j < i; j++) {
                    movements[j].reset();
                }
                pathPosition = i;
                ticksOnCurrent = 0;
                stuckTicks = 0;
                break;
            }
        }

        // 2. Stuck detection (every 10 ticks): check if bot has actually moved
        if (totalTicks % 10 == 0) {
            double moved = Math.sqrt(
                    (bot.getX() - lastX) * (bot.getX() - lastX) +
                    (bot.getY() - lastY) * (bot.getY() - lastY) +
                    (bot.getZ() - lastZ) * (bot.getZ() - lastZ));
            if (moved < MOVE_THRESHOLD) {
                stuckTicks += 10;
            } else {
                stuckTicks = Math.max(0, stuckTicks - 5);
            }
            lastX = bot.getX();
            lastY = bot.getY();
            lastZ = bot.getZ();
        }

        if (stuckTicks >= STUCK_THRESHOLD || ticksOnCurrent >= MAX_TICKS_PER_MOVEMENT) {
            BridgeMod.LOGGER.debug("PathExecutor: stuck (stuckTicks={}, ticksOnCurrent={}) at movement {}/{}",
                    stuckTicks, ticksOnCurrent, pathPosition, movements.length);
            bot.clearMovementInput();
            bot.setSprinting(false);
            return Status.FAILED_STUCK;
        }

        // 3. Horizontal deviation check (keeps existing logic that works well)
        double minHorizDistSq = Double.MAX_VALUE;
        int searchStart = Math.max(0, pathPosition);
        int searchEnd = Math.min(path.size(), pathPosition + 7);
        for (int i = searchStart; i < searchEnd; i++) {
            PathNode node = path.get(i);
            double ndx = (node.x + 0.5) - bot.getX();
            double ndz = (node.z + 0.5) - bot.getZ();
            double hDistSq = ndx * ndx + ndz * ndz;
            if (hDistSq < minHorizDistSq) {
                minHorizDistSq = hDistSq;
            }
        }
        double closestHorizDist = Math.sqrt(minHorizDistSq);
        if (closestHorizDist > MAX_MAX_DIST_FROM_PATH) {
            bot.clearMovementInput();
            bot.setSprinting(false);
            return Status.FAILED_DEVIATED;
        }
        if (closestHorizDist > MAX_DIST_FROM_PATH) {
            ticksAway++;
            if (ticksAway > MAX_TICKS_AWAY) {
                bot.clearMovementInput();
                bot.setSprinting(false);
                return Status.FAILED_DEVIATED;
            }
        } else {
            ticksAway = 0;
        }

        // 4. Execute current movement.
        // Baritone uses recursive onTick() when a movement completes — we use a loop.
        // This allows multiple movements to complete in a single tick (e.g., when the bot
        // is already at the destination of the next movement).
        while (pathPosition < movements.length) {
            Movement movement = movements[pathPosition];
            MovementStatus mStatus = movement.update();

            if (mStatus == MovementStatus.SUCCESS) {
                BridgeMod.LOGGER.debug("PathExecutor: movement {}/{} SUCCESS ({} → {})",
                        pathPosition, movements.length,
                        formatPos(movement.getSrc()), formatPos(movement.getDest()));
                pathPosition++;
                ticksOnCurrent = 0;
                stuckTicks = 0;
                bot.clearMovementInput();
                bot.setSprinting(false);

                if (pathPosition >= movements.length) {
                    return Status.COMPLETED;
                }
                // Immediately try the next movement (Baritone's recursive onTick)
                continue;
            }

            if (mStatus == MovementStatus.UNREACHABLE) {
                BridgeMod.LOGGER.debug("PathExecutor: movement {}/{} UNREACHABLE ({} → {})",
                        pathPosition, movements.length,
                        formatPos(movement.getSrc()), formatPos(movement.getDest()));
                bot.clearMovementInput();
                bot.setSprinting(false);
                return Status.FAILED_STUCK;
            }

            // RUNNING: movement is in progress, continue next tick
            break;
        }

        return Status.IN_PROGRESS;
    }

    // ==================== Movement Factory ====================

    /**
     * Create the appropriate Movement execution object based on the PathNode's moveType.
     * Each movement type has specific execution logic ported from Baritone.
     */
    private static Movement createMovement(FakePlayer bot, PathNode from, PathNode to) {
        BlockPos src = new BlockPos(from.x, from.y, from.z);
        BlockPos dest = new BlockPos(to.x, to.y, to.z);
        Moves moveType = to.moveType;

        if (moveType == null) {
            // Start node or unknown — infer from position delta
            return inferMovement(bot, src, dest);
        }

        switch (moveType) {
            case TRAVERSE_NORTH:
            case TRAVERSE_SOUTH:
            case TRAVERSE_EAST:
            case TRAVERSE_WEST:
                return new MovementTraverse(bot, src, dest);

            case ASCEND_NORTH:
            case ASCEND_SOUTH:
            case ASCEND_EAST:
            case ASCEND_WEST:
                return new MovementAscend(bot, src, dest);

            case DESCEND_NORTH:
            case DESCEND_SOUTH:
            case DESCEND_EAST:
            case DESCEND_WEST:
                return new MovementDescend(bot, src, dest);

            case DIAGONAL_NORTHEAST:
            case DIAGONAL_NORTHWEST:
            case DIAGONAL_SOUTHEAST:
            case DIAGONAL_SOUTHWEST:
                return new MovementDiagonal(bot, src, dest);

            case PILLAR_UP:
                return new MovementPillar(bot, src, dest);

            case PARKOUR_NORTH:
            case PARKOUR_SOUTH:
            case PARKOUR_EAST:
            case PARKOUR_WEST:
                return new MovementParkour(bot, src, dest);

            default:
                return inferMovement(bot, src, dest);
        }
    }

    /**
     * Infer the movement type from src/dest positions when moveType is not available.
     */
    private static Movement inferMovement(FakePlayer bot, BlockPos src, BlockPos dest) {
        int dx = Math.abs(dest.getX() - src.getX());
        int dy = dest.getY() - src.getY();
        int dz = Math.abs(dest.getZ() - src.getZ());

        if (dy > 0 && dx == 0 && dz == 0) {
            return new MovementPillar(bot, src, dest);
        } else if (dy > 0) {
            return new MovementAscend(bot, src, dest);
        } else if (dy < 0) {
            return new MovementDescend(bot, src, dest);
        } else if (dy == 0 && (dx + dz >= 2) && (dx == 0 || dz == 0)) {
            // Cardinal 2-3 block gap: parkour sprint-jump
            return new MovementParkour(bot, src, dest);
        } else if (dx + dz == 2) {
            return new MovementDiagonal(bot, src, dest);
        } else {
            return new MovementTraverse(bot, src, dest);
        }
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    // ==================== Accessors ====================

    public int getPathIndex() {
        return pathPosition;
    }

    public int getPathLength() {
        return path.size();
    }

    public int getTotalTicks() {
        return totalTicks;
    }

    public List<PathNode> getPath() {
        return path;
    }
}
