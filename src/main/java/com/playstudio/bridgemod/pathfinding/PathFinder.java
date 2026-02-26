package com.playstudio.bridgemod.pathfinding;

import com.playstudio.bridgemod.BridgeMod;
import com.playstudio.bridgemod.pathfinding.goals.Goal;
import com.playstudio.bridgemod.pathfinding.moves.MoveResult;
import com.playstudio.bridgemod.pathfinding.moves.Moves;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.*;

/**
 * A* pathfinder.
 * Ported from Baritone's AStarPathFinder + AbstractNodeCostSearch.
 *
 * Uses Long2ObjectOpenHashMap (fastutil, bundled with Minecraft) for node storage.
 * This avoids Long autoboxing that HashMap<Long, PathNode> creates on every lookup,
 * and provides better cache locality via open-addressing.
 */
public class PathFinder {

    // Timeouts (Baritone uses configurable timeouts, we use fixed defaults)
    private static final long PRIMARY_TIMEOUT_MS = 2000;
    private static final long FAILURE_TIMEOUT_MS = 5000;

    // bestSoFar coefficients (from Baritone's AbstractNodeCostSearch)
    private static final double[] COEFFICIENTS = {1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0};

    // Minimum distance from start for bestSoFar to count as a valid partial path.
    // Baritone uses 5.0 but that requires 5+ steps which is too strict for mining descent
    // (each step costs ~40 ticks, hard to find 5+ steps within timeout).
    // 3.0 → dist²>9, requires 3+ descent steps (dy=-3, dz=±1 → dist²=10).
    private static final double MIN_DIST_PATH = 3.0;

    // Minimum improvement for node relaxation (Baritone setting)
    private static final double MIN_IMPROVEMENT = 0.01;

    // Timeout check interval: every 64 nodes (Baritone: 1 << 6)
    private static final int TIME_CHECK_INTERVAL = 1 << 6;

    private final int startX, startY, startZ;
    private final Goal goal;
    private final CalculationContext ctx;

    // Node storage (fastutil open-addressing map: no Long autoboxing, better cache locality)
    private final Long2ObjectOpenHashMap<PathNode> map;
    private final PathNode[] bestSoFar;
    private final double[] bestHeuristicSoFar;

    public PathFinder(int startX, int startY, int startZ, Goal goal, CalculationContext ctx) {
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.goal = goal;
        this.ctx = ctx;
        this.map = new Long2ObjectOpenHashMap<>(4096, 0.75f);
        this.bestSoFar = new PathNode[COEFFICIENTS.length];
        this.bestHeuristicSoFar = new double[COEFFICIENTS.length];
    }

    /**
     * Get or create a PathNode at the given position.
     * Equivalent to Baritone's AbstractNodeCostSearch.getNodeAtPosition().
     */
    private PathNode getNodeAtPosition(int x, int y, int z, long hashCode) {
        PathNode existing = map.get(hashCode);
        if (existing != null) {
            return existing;
        }
        PathNode newNode = new PathNode(x, y, z, goal);
        map.put(hashCode, newNode);
        return newNode;
    }

    private double getDistFromStartSq(PathNode node) {
        int dx = node.x - startX;
        int dy = node.y - startY;
        int dz = node.z - startZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Run the A* search.
     * 100% ported from Baritone's AStarPathFinder.calculate0().
     */
    public PathResult calculate() {
        long startTime = System.currentTimeMillis();

        BridgeMod.LOGGER.debug("PathFinder starting from ({},{},{}) to {}", startX, startY, startZ, goal);

        // Initialize start node
        PathNode startNode = getNodeAtPosition(startX, startY, startZ, PathNode.longHash(startX, startY, startZ));
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal;

        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);

        // Initialize bestSoFar tracking
        for (int i = 0; i < bestHeuristicSoFar.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }

        MoveResult res = new MoveResult();
        long primaryTimeoutTime = startTime + PRIMARY_TIMEOUT_MS;
        long failureTimeoutTime = startTime + FAILURE_TIMEOUT_MS;
        boolean failing = true;
        int numNodes = 0;
        int numMovementsConsidered = 0;
        Moves[] allMoves = Moves.values();
        int minY = ctx.getLevel().getMinBuildHeight();
        int maxY = ctx.getLevel().getMaxBuildHeight();

        while (!openSet.isEmpty()) {
            // Timeout check every TIME_CHECK_INTERVAL nodes (Baritone: every 64)
            if ((numNodes & (TIME_CHECK_INTERVAL - 1)) == 0 && numNodes > 0) {
                long now = System.currentTimeMillis();
                if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
                    BridgeMod.LOGGER.debug("PathFinder timed out after {}ms, {} nodes explored",
                            now - startTime, numNodes);
                    break;
                }
            }

            PathNode currentNode = openSet.removeLowest();
            numNodes++;

            // Goal check
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                long elapsed = System.currentTimeMillis() - startTime;
                BridgeMod.LOGGER.info("PathFinder found goal at ({},{},{}) in {}ms, {} movements",
                        currentNode.x, currentNode.y, currentNode.z, elapsed, numMovementsConsidered);
                List<PathNode> path = reconstructPath(startNode, currentNode);
                return new PathResult(path, true, numNodes, elapsed);
            }

            // Explore neighbors
            for (Moves moves : allMoves) {
                int newX = currentNode.x + moves.xOffset;
                int newZ = currentNode.z + moves.zOffset;

                // Chunk loading check (only if crossing chunk boundary)
                if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4)
                        && !ctx.isLoaded(newX, newZ)) {
                    continue;
                }

                // Y bounds check
                if (currentNode.y + moves.yOffset > maxY || currentNode.y + moves.yOffset < minY) {
                    continue;
                }

                res.reset();
                moves.apply(ctx, currentNode.x, currentNode.y, currentNode.z, res);
                numMovementsConsidered++;
                double actionCost = res.cost;

                if (actionCost >= ActionCosts.COST_INF) {
                    continue;
                }
                if (actionCost <= 0 || Double.isNaN(actionCost)) {
                    throw new IllegalStateException(moves + " calculated implausible cost " + actionCost);
                }

                long hashCode = PathNode.longHash(res.x, res.y, res.z);
                PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
                double tentativeCost = currentNode.cost + actionCost;

                if (neighbor.cost - tentativeCost > MIN_IMPROVEMENT) {
                    neighbor.previous = currentNode;
                    neighbor.cost = tentativeCost;
                    neighbor.moveType = moves;
                    neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);
                    }
                    // Update bestSoFar
                    for (int i = 0; i < COEFFICIENTS.length; i++) {
                        double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
                        if (bestHeuristicSoFar[i] - heuristic > MIN_IMPROVEMENT) {
                            bestHeuristicSoFar[i] = heuristic;
                            bestSoFar[i] = neighbor;
                            if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                                failing = false;
                            }
                        }
                    }
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        BridgeMod.LOGGER.info("PathFinder: {} movements considered, {} nodes, map size {}",
                numMovementsConsidered, numNodes, map.size());

        // Return best partial path (bestSoFar mechanism)
        Optional<List<PathNode>> bestPath = bestSoFar(numNodes);
        if (bestPath.isPresent()) {
            BridgeMod.LOGGER.info("PathFinder: partial path with {} nodes in {}ms",
                    bestPath.get().size(), elapsed);
            return new PathResult(bestPath.get(), false, numNodes, elapsed);
        }

        BridgeMod.LOGGER.warn("PathFinder: no path found in {}ms ({} explored)", elapsed, numNodes);
        return new PathResult(Collections.emptyList(), false, numNodes, elapsed);
    }

    /**
     * Get the best partial path from bestSoFar nodes.
     * Ported from Baritone's AbstractNodeCostSearch.bestSoFar().
     *
     * Iterates coefficients from lowest (1.5, most balanced cost/heuristic trade-off)
     * to highest (10.0, most heuristic-guided). Returns the FIRST coefficient's
     * bestSoFar that is far enough from start (dist > MIN_DIST_PATH).
     * If none are far enough, returns empty (no partial path).
     */
    private Optional<List<PathNode>> bestSoFar(int numNodes) {
        PathNode startNode = getNodeAtPosition(startX, startY, startZ, PathNode.longHash(startX, startY, startZ));
        double minDistSq = MIN_DIST_PATH * MIN_DIST_PATH;
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            if (bestSoFar[i] == null) continue;
            if (getDistFromStartSq(bestSoFar[i]) <= minDistSq) continue;
            List<PathNode> path = reconstructPath(startNode, bestSoFar[i]);
            if (path.size() <= 1) continue;
            return Optional.of(path);
        }
        return Optional.empty();
    }

    /**
     * Reconstruct path from start to end node.
     */
    private List<PathNode> reconstructPath(PathNode startNode, PathNode endNode) {
        List<PathNode> path = new ArrayList<>();
        PathNode node = endNode;
        while (node != null) {
            path.add(node);
            node = node.previous;
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * Result of a pathfinding calculation.
     */
    public static class PathResult {
        public final List<PathNode> path;
        public final boolean reachedGoal;
        public final int nodesExplored;
        public final long timeMs;

        public PathResult(List<PathNode> path, boolean reachedGoal, int nodesExplored, long timeMs) {
            this.path = path;
            this.reachedGoal = reachedGoal;
            this.nodesExplored = nodesExplored;
            this.timeMs = timeMs;
        }
    }
}
