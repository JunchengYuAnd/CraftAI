package com.playstudio.bridgemod.bot;

import com.playstudio.bridgemod.BridgeMod;
import com.playstudio.bridgemod.pathfinding.CalculationContext;
import com.playstudio.bridgemod.pathfinding.PathExecutor;
import com.playstudio.bridgemod.pathfinding.PathFinder;
import com.playstudio.bridgemod.pathfinding.PathNode;
import com.playstudio.bridgemod.pathfinding.goals.Goal;
import com.playstudio.bridgemod.pathfinding.goals.GoalBlock;
import com.playstudio.bridgemod.pathfinding.goals.GoalNear;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Controls a single FakePlayer's movement and actions each tick.
 * Uses A* pathfinding (Phase 3B) for navigation.
 *
 * Flow:
 * 1. startGoto() sets goal, triggers async path calculation
 * 2. tick() checks calculation result, runs PathExecutor
 * 3. On stuck/deviated: recalculates path
 * 4. On partial path complete: uses lookahead to seamlessly continue
 *
 * Lookahead (Baritone-style):
 * When executing a partial path segment, we pre-calculate the next segment
 * before the current one finishes. This eliminates the pause between segments.
 */
public class BotController {

    private final FakePlayer bot;

    // Navigation state
    private volatile boolean navigating = false;
    private Goal goal;
    private int goalRange = 2;

    // Pathfinding
    private CompletableFuture<PathFinder.PathResult> pendingCalculation;
    private PathExecutor currentExecutor;
    private int recalcCount = 0;
    private static final int MAX_RECALCS = 25; // generous limit for long-distance paths

    // Lookahead: start pre-calculating next segment when this many nodes remain
    private static final int LOOKAHEAD_NODES = 5;

    // Same-position deviation detection: if bot keeps deviating from the same spot,
    // it's stuck (not just off-path)
    private int lastDeviationX, lastDeviationY, lastDeviationZ;
    private int samePositionDeviationCount = 0;
    private static final int MAX_SAME_POS_DEVIATIONS = 3;

    // Pending goto response
    private volatile BiConsumer<Boolean, String> pendingCallback;

    public BotController(FakePlayer bot) {
        this.bot = bot;
    }

    /**
     * Start navigating to a target position.
     * The callback will be invoked with (success, reason) when navigation completes.
     */
    public void startGoto(double x, double y, double z, int range, BiConsumer<Boolean, String> callback) {
        this.goalRange = range;
        this.pendingCallback = callback;
        this.recalcCount = 0;
        this.samePositionDeviationCount = 0;
        this.currentExecutor = null;
        this.navigating = true;

        // Create goal
        int bx = BlockPos.containing(x, y, z).getX();
        int by = BlockPos.containing(x, y, z).getY();
        int bz = BlockPos.containing(x, y, z).getZ();

        if (range <= 1) {
            this.goal = new GoalBlock(bx, by, bz);
        } else {
            this.goal = new GoalNear(bx, by, bz, range);
        }

        BridgeMod.LOGGER.info("Bot '{}' starting goto {} range={}",
                bot.getBotName(), goal, range);

        // Start path calculation
        startPathCalculation();
    }

    /**
     * Stop current navigation.
     */
    public void stop() {
        if (navigating) {
            navigating = false;
            clearMovement();
            if (pendingCalculation != null) {
                pendingCalculation.cancel(true);
                pendingCalculation = null;
            }
            currentExecutor = null;
            BiConsumer<Boolean, String> cb = pendingCallback;
            pendingCallback = null;
            if (cb != null) {
                cb.accept(false, "cancelled");
            }
            BridgeMod.LOGGER.debug("Bot '{}' stopped", bot.getBotName());
        }
    }

    /**
     * Called every server tick to update bot behavior.
     * Must run on the server thread.
     *
     * Tick order:
     * 1. Goal check (already arrived?)
     * 2. Process completed path calculation (lookahead or fresh)
     * 3. Execute current path + trigger lookahead if near end
     */
    public void tick() {
        if (!navigating) {
            return;
        }

        if (!bot.isAlive()) {
            completeNavigation(false, "died");
            return;
        }

        // 1. Check if we've reached the goal
        int bx = BlockPos.containing(bot.getX(), bot.getY(), bot.getZ()).getX();
        int by = BlockPos.containing(bot.getX(), bot.getY(), bot.getZ()).getY();
        int bz = BlockPos.containing(bot.getX(), bot.getY(), bot.getZ()).getZ();
        if (goal.isInGoal(bx, by, bz)) {
            clearMovement();
            completeNavigation(true, null);
            return;
        }

        // 2. Process completed path calculation (if any)
        // This is checked BEFORE executing the path so that lookahead results
        // are picked up immediately, minimizing the gap between segments.
        if (pendingCalculation != null && pendingCalculation.isDone()) {
            handleCompletedCalculation();
        }

        // 3. Execute current path
        if (currentExecutor != null) {
            PathExecutor.Status status = currentExecutor.tick();

            switch (status) {
                case IN_PROGRESS:
                    // Lookahead: pre-calculate next segment when approaching end of partial path.
                    // This is the key to eliminating pauses between path segments.
                    if (pendingCalculation == null) {
                        List<PathNode> currentPath = currentExecutor.getPath();
                        int remaining = currentPath.size() - currentExecutor.getPathIndex();
                        PathNode lastNode = currentPath.get(currentPath.size() - 1);
                        if (remaining <= LOOKAHEAD_NODES && !goal.isInGoal(lastNode.x, lastNode.y, lastNode.z)) {
                            BridgeMod.LOGGER.debug("Bot '{}' lookahead: {} nodes remaining, pre-calculating",
                                    bot.getBotName(), remaining);
                            startPathCalculation();
                        }
                    }
                    break;

                case COMPLETED:
                    // Path segment complete - check if we reached the actual goal
                    int cx = BlockPos.containing(bot.getX(), bot.getY(), bot.getZ()).getX();
                    int cy = BlockPos.containing(bot.getX(), bot.getY(), bot.getZ()).getY();
                    int cz = BlockPos.containing(bot.getX(), bot.getY(), bot.getZ()).getZ();
                    if (goal.isInGoal(cx, cy, cz)) {
                        clearMovement();
                        completeNavigation(true, null);
                    } else if (pendingCalculation != null) {
                        // Lookahead calculation already in progress - just drop the executor.
                        // Next tick will pick up the completed calculation seamlessly.
                        BridgeMod.LOGGER.info("Bot '{}' partial path complete, lookahead in progress",
                                bot.getBotName());
                        currentExecutor = null;
                    } else {
                        // No lookahead was triggered (short path or path reached goal area)
                        BridgeMod.LOGGER.info("Bot '{}' partial path complete at ({},{},{}), recalculating",
                                bot.getBotName(), (int)bot.getX(), (int)bot.getY(), (int)bot.getZ());
                        currentExecutor = null;
                        recalculate("partial_path");
                    }
                    break;

                case FAILED_STUCK:
                    BridgeMod.LOGGER.info("Bot '{}' stuck on path at ({},{},{}), node {}/{}, recalculating",
                            bot.getBotName(), (int)bot.getX(), (int)bot.getY(), (int)bot.getZ(),
                            currentExecutor.getPathIndex(), currentExecutor.getPathLength());
                    currentExecutor = null;
                    cancelPendingCalculation(); // discard stale lookahead
                    recalculate("stuck");
                    break;

                case FAILED_DEVIATED:
                    int dx = (int) bot.getX();
                    int dy = (int) bot.getY();
                    int dz = (int) bot.getZ();
                    // Detect same-position deviation loop
                    if (dx == lastDeviationX && dy == lastDeviationY && dz == lastDeviationZ) {
                        samePositionDeviationCount++;
                    } else {
                        samePositionDeviationCount = 1;
                        lastDeviationX = dx;
                        lastDeviationY = dy;
                        lastDeviationZ = dz;
                    }
                    if (samePositionDeviationCount >= MAX_SAME_POS_DEVIATIONS) {
                        BridgeMod.LOGGER.info("Bot '{}' stuck at ({},{},{}) - deviated {} times from same position",
                                bot.getBotName(), dx, dy, dz, samePositionDeviationCount);
                        currentExecutor = null;
                        clearMovement();
                        completeNavigation(false, "stuck_at_position");
                        break;
                    }
                    BridgeMod.LOGGER.info("Bot '{}' deviated from path at ({},{},{}), recalculating",
                            bot.getBotName(), dx, dy, dz);
                    currentExecutor = null;
                    cancelPendingCalculation(); // discard stale lookahead
                    recalculate("deviated");
                    break;
            }
        }
        // else: no executor and pending calculation → brief pause (1-2 ticks max with chunk caching)
    }

    /**
     * Process a completed async path calculation.
     * Handles both fresh calculations and lookahead results.
     */
    private void handleCompletedCalculation() {
        try {
            PathFinder.PathResult result = pendingCalculation.get();
            pendingCalculation = null;

            if (result.path.isEmpty()) {
                if (currentExecutor == null) {
                    // No path and no current execution - navigation failed
                    clearMovement();
                    completeNavigation(false, "no_path");
                }
                // If we have a current executor (lookahead returned empty), just keep going.
                // The executor's COMPLETED handler will trigger a fresh recalculation.
                return;
            }

            // Validate path start: discard if bot moved too far during calculation.
            // Only matters when there's no current executor (otherwise the path is
            // a lookahead and the executor will naturally transition to it).
            PathNode firstNode = result.path.get(0);
            double startDist = Math.sqrt(
                    (firstNode.x + 0.5 - bot.getX()) * (firstNode.x + 0.5 - bot.getX()) +
                    (firstNode.y - bot.getY()) * (firstNode.y - bot.getY()) +
                    (firstNode.z + 0.5 - bot.getZ()) * (firstNode.z + 0.5 - bot.getZ()));
            if (startDist > 5.0 && currentExecutor == null) {
                BridgeMod.LOGGER.info("Bot '{}' moved {} blocks from path start during calculation, recalculating",
                        bot.getBotName(), String.format("%.1f", startDist));
                recalculate("moved_from_start");
                return;
            }

            // Install the new path executor (replaces current if this is a lookahead)
            currentExecutor = new PathExecutor(bot, result.path);
            BridgeMod.LOGGER.info("Bot '{}' executing path: {} nodes, reachedGoal={}, {}ms, {} explored",
                    bot.getBotName(), result.path.size(), result.reachedGoal,
                    result.timeMs, result.nodesExplored);

        } catch (Exception e) {
            BridgeMod.LOGGER.error("Bot '{}' path calculation failed: {}",
                    bot.getBotName(), e.getMessage());
            pendingCalculation = null;
            if (currentExecutor == null) {
                clearMovement();
                completeNavigation(false, "path_error");
            }
            // If we have a current executor, keep going - the COMPLETED handler will retry
        }
    }

    /**
     * Start an async path calculation from the bot's current position.
     *
     * KEY: CalculationContext + chunk caching are created on the SERVER THREAD,
     * then the A* search runs on a background thread reading from cached chunks.
     * This avoids ServerChunkCache's thread dispatch (which was causing
     * every block read to round-trip to the main thread → 100x slowdown).
     */
    private void startPathCalculation() {
        int startX = BlockPos.containing(bot.getX(), bot.getY(), bot.getZ()).getX();
        int startY = BlockPos.containing(bot.getX(), bot.getY(), bot.getZ()).getY();
        int startZ = BlockPos.containing(bot.getX(), bot.getY(), bot.getZ()).getZ();

        ServerLevel level = bot.serverLevel();
        boolean canSprint = bot.getFoodData().getFoodLevel() > 6;

        // Create context and pre-cache chunks ON THE SERVER THREAD
        CalculationContext ctx = new CalculationContext(level, canSprint);
        ctx.cacheChunksNearby(startX, startZ);

        // A* search runs on background thread, reading from cached chunk data
        pendingCalculation = CompletableFuture.supplyAsync(() -> {
            PathFinder finder = new PathFinder(startX, startY, startZ, goal, ctx);
            return finder.calculate();
        });
    }

    /**
     * Recalculate path (after stuck, deviation, or partial path).
     */
    private void recalculate(String reason) {
        recalcCount++;
        if (recalcCount > MAX_RECALCS) {
            clearMovement();
            completeNavigation(false, "too_many_recalculations");
            return;
        }
        BridgeMod.LOGGER.info("Bot '{}' recalculating path (#{}, reason={})",
                bot.getBotName(), recalcCount, reason);
        startPathCalculation();
    }

    private void cancelPendingCalculation() {
        if (pendingCalculation != null) {
            pendingCalculation.cancel(true);
            pendingCalculation = null;
        }
    }

    private void clearMovement() {
        bot.clearMovementInput();
        bot.setSprinting(false);
    }

    private void completeNavigation(boolean success, String reason) {
        navigating = false;
        currentExecutor = null;
        cancelPendingCalculation();
        BiConsumer<Boolean, String> cb = pendingCallback;
        pendingCallback = null;
        if (cb != null) {
            cb.accept(success, reason);
        }
        if (success) {
            BridgeMod.LOGGER.info("Bot '{}' arrived at goal", bot.getBotName());
        } else {
            BridgeMod.LOGGER.info("Bot '{}' navigation failed: {}", bot.getBotName(), reason);
        }
    }

    public boolean isNavigating() {
        return navigating;
    }

    public FakePlayer getBot() {
        return bot;
    }

    /** Get the current path being executed (for rendering). */
    public java.util.List<PathNode> getCurrentPath() {
        PathExecutor exec = currentExecutor;
        return exec != null ? exec.getPath() : null;
    }

    /** Get the current index in the path (for rendering). */
    public int getCurrentPathIndex() {
        PathExecutor exec = currentExecutor;
        return exec != null ? exec.getPathIndex() : 0;
    }
}
