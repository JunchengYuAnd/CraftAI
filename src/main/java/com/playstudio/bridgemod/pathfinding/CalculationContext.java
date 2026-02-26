package com.playstudio.bridgemod.pathfinding;

import com.playstudio.bridgemod.bot.FakePlayer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * World state accessor for pathfinding calculations.
 * Adapted from Baritone's CalculationContext + BlockStateInterface.
 *
 * Optimizations:
 * 1. Pre-caches LevelChunk references on server thread (avoids ServerChunkCache thread dispatch)
 * 2. Single-chunk hot-cache (Baritone's BlockStateInterface.prev pattern) - eliminates ~85% of map lookups
 * 3. Long2ObjectOpenHashMap for chunk cache (no Long autoboxing)
 * 4. PrecomputedData reference for fast block state flag lookups
 */
public class CalculationContext {

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<LevelChunk> chunkCache;

    // Single-chunk hot-cache (Baritone's BlockStateInterface.prev pattern).
    // A* has extreme spatial locality - consecutive lookups are almost always in the same chunk.
    // This avoids the hash map lookup ~85% of the time.
    private LevelChunk prevChunk;
    private int prevChunkX = Integer.MIN_VALUE;
    private int prevChunkZ = Integer.MIN_VALUE;

    // Pre-computed block state flags (eliminates instanceof chains in MovementHelper)
    public final PrecomputedData precomputed;

    // Player capabilities (snapshotted at creation)
    public final boolean canSprint;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final int maxFallHeightNoWater;
    public final double waterWalkSpeed;
    public final double jumpPenalty;
    // Player reference for mining cost calculation (Phase 3C)
    public final FakePlayer player;  // nullable
    // Block placement during pathfinding (Phase 3C bridge/pillar)
    public final boolean hasThrowawayBlock;

    // Best-tool mining cost: hotbar snapshot + player effect multipliers.
    // Snapshotted on server thread so A* background thread can compute
    // mining costs using the best available tool (matching FakePlayer.selectBestTool).
    private final ItemStack[] hotbarSnapshot;  // nullable if no player
    private final float playerMiningMultiplier; // Haste/Fatigue combined

    // Water features (Baritone)
    public final boolean assumeWalkOnWater;       // Jesus mode / Frost Walker
    public final double walkOnWaterOnePenalty;     // Extra cost for walking on water surface in Jesus mode

    // Y bounds cached
    private final int minY;
    private final int maxY;

    // Reusable positions to avoid GC pressure in hot path
    private final BlockPos.MutableBlockPos pos1 = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos pos2 = new BlockPos.MutableBlockPos();

    public CalculationContext(ServerLevel level, boolean canSprint) {
        this(level, canSprint, null);
    }

    public CalculationContext(ServerLevel level, boolean canSprint, LivingEntity player) {
        this.level = level;
        this.chunkCache = new Long2ObjectOpenHashMap<>(512, 0.5f);
        this.precomputed = PrecomputedData.getInstance();
        this.canSprint = canSprint;
        this.player = (player instanceof FakePlayer fp) ? fp : null;
        this.hasThrowawayBlock = (this.player != null && this.player.hasThrowawayBlock());
        // Snapshot hotbar + player effects for thread-safe best-tool mining cost
        if (this.player != null) {
            this.hotbarSnapshot = new ItemStack[9];
            for (int i = 0; i < 9; i++) {
                this.hotbarSnapshot[i] = this.player.getInventory().getItem(i).copy();
            }
            float mult = 1.0f;
            if (MobEffectUtil.hasDigSpeed(this.player)) {
                mult *= 1.0f + (MobEffectUtil.getDigSpeedAmplification(this.player) + 1) * 0.2f;
            }
            if (this.player.hasEffect(MobEffects.DIG_SLOWDOWN)) {
                mult *= switch (this.player.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()) {
                    case 0 -> 0.3f;
                    case 1 -> 0.09f;
                    case 2 -> 0.0027f;
                    default -> 8.1E-4f;
                };
            }
            this.playerMiningMultiplier = mult;
        } else {
            this.hotbarSnapshot = null;
            this.playerMiningMultiplier = 1.0f;
        }
        // Match Baritone defaults
        this.allowDiagonalDescend = true;
        this.allowDiagonalAscend = true;
        this.maxFallHeightNoWater = 3;  // Baritone default: 3 (no fall damage)
        this.jumpPenalty = 2.0;  // Baritone default: discourages unnecessary jumping
        // Water features
        this.assumeWalkOnWater = false;  // Phase 3C: set based on Frost Walker enchantment
        this.walkOnWaterOnePenalty = 3.0;  // Baritone default: extra cost for Jesus mode water surface
        // Depth Strider: linear interpolation between water speed and land speed
        if (player != null) {
            int depth = EnchantmentHelper.getEnchantmentLevel(Enchantments.DEPTH_STRIDER, player);
            depth = Math.min(depth, 3);
            if (depth > 0) {
                float mult = depth / 3.0f;
                this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST * (1 - mult)
                        + ActionCosts.WALK_ONE_BLOCK_COST * mult;
            } else {
                this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST;
            }
        } else {
            this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST;  // No Depth Strider
        }
        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight();
    }

    /**
     * Pre-cache loaded chunks around the starting position.
     * MUST be called on the server thread before starting the A* search.
     * This is the key to making background-thread pathfinding fast:
     * LevelChunk objects are safe to read from any thread (array-backed),
     * but ServerChunkCache.getChunk() dispatches to the main thread.
     */
    public void cacheChunksNearby(int centerX, int centerZ) {
        ServerChunkCache chunkSource = level.getChunkSource();
        int radiusChunks = 8; // ~128 blocks in each direction
        int centerCX = centerX >> 4;
        int centerCZ = centerZ >> 4;
        for (int cx = centerCX - radiusChunks; cx <= centerCX + radiusChunks; cx++) {
            for (int cz = centerCZ - radiusChunks; cz <= centerCZ + radiusChunks; cz++) {
                LevelChunk chunk = chunkSource.getChunkNow(cx, cz);
                if (chunk != null) {
                    chunkCache.put(ChunkPos.asLong(cx, cz), chunk);
                }
            }
        }
    }

    /**
     * Get the block state at (x,y,z).
     * Uses single-chunk hot-cache to avoid hash map lookup for consecutive
     * same-chunk accesses (which is ~85% of all accesses during A*).
     * Safe to call from background thread (reads from pre-cached LevelChunk objects).
     */
    public BlockState get(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        int cx = x >> 4;
        int cz = z >> 4;
        LevelChunk chunk;
        if (cx == prevChunkX && cz == prevChunkZ) {
            chunk = prevChunk; // Hot-cache hit (~85% of calls)
        } else {
            chunk = chunkCache.get(ChunkPos.asLong(cx, cz));
            prevChunk = chunk;
            prevChunkX = cx;
            prevChunkZ = cz;
        }
        if (chunk == null) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        return chunk.getBlockState(pos1.set(x, y, z));
    }

    /**
     * Equivalent to Baritone's isBlockNormalCube (1.12.2).
     * Returns true only for full solid cubes (stone, dirt, planks, etc.).
     *
     * Uses canOcclude() as a fast path - this is a cached boolean property
     * that's true for all opaque full cubes. For non-occluding full blocks
     * (glass etc.), we fall back to collision shape check.
     */
    public boolean isFullBlock(int x, int y, int z) {
        BlockState state = get(x, y, z);
        // Fast path: canOcclude() is cached and covers stone, dirt, logs, ores, etc.
        if (state.canOcclude()) return true;
        // Slow path: collision shape check for non-opaque full blocks
        pos2.set(x, y, z);
        return state.isCollisionShapeFullBlock(level, pos2);
    }

    /**
     * Equivalent to Baritone's block.isPassable(world, pos) (1.12.2).
     * Returns true if the block has NO collision shape.
     */
    public boolean isPassable(int x, int y, int z) {
        BlockState state = get(x, y, z);
        // Fast paths
        if (state.isAir()) return true;
        if (state.canOcclude()) return false;
        // Collision shape check for remaining blocks
        pos2.set(x, y, z);
        return state.getCollisionShape(level, pos2).isEmpty();
    }

    /**
     * Check if a chunk is loaded (i.e., cached) at the given XZ coordinates.
     * Also uses hot-cache for the common case.
     */
    public boolean isLoaded(int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        if (cx == prevChunkX && cz == prevChunkZ) {
            return prevChunk != null;
        }
        return chunkCache.containsKey(ChunkPos.asLong(cx, cz));
    }

    /**
     * Compute the destroy progress using the best tool in the player's hotbar.
     * Replicates vanilla's Player.getDestroySpeed() + BlockState.getDestroyProgress()
     * but scans all hotbar slots instead of using only the currently held item.
     * Thread-safe: reads from snapshotted hotbar, never modifies player state.
     *
     * @return progress per tick (0 = unbreakable), same scale as getDestroyProgress()
     */
    public float bestDestroyProgress(BlockState state, BlockPos pos) {
        if (hotbarSnapshot == null) return 0;

        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) return 0;

        float bestProgress = 0;
        for (ItemStack stack : hotbarSnapshot) {
            // Base tool speed (bare hand = 1.0)
            float speed = stack.isEmpty() ? 1.0f : stack.getDestroySpeed(state);

            // Efficiency enchantment (per-item)
            if (!stack.isEmpty()) {
                int eff = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, stack);
                if (eff > 0) {
                    speed += eff * eff + 1;
                }
            }

            // Player-level multiplier (Haste/Mining Fatigue, snapshotted)
            speed *= playerMiningMultiplier;

            // Harvest check: determines 30x vs 100x divisor
            boolean canHarvest = !state.requiresCorrectToolForDrops()
                    || (!stack.isEmpty() && stack.isCorrectToolForDrops(state));
            int divisor = canHarvest ? 30 : 100;

            float progress = speed / hardness / divisor;
            if (progress > bestProgress) {
                bestProgress = progress;
            }
        }
        return bestProgress;
    }

    public ServerLevel getLevel() {
        return level;
    }
}
