package com.playstudio.bridgemod.bot;

import com.mojang.authlib.GameProfile;
import com.playstudio.bridgemod.BridgeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.function.BiConsumer;

/**
 * A fake ServerPlayer entity controlled by the AI via WebSocket.
 * Based on Carpet mod's EntityPlayerMPFake pattern.
 *
 * Movement is controlled via setMovementInput() which overrides the travel() vector,
 * bypassing the client Input system (which doesn't exist for fake players).
 */
public class FakePlayer extends ServerPlayer {

    private final String botName;

    // Movement override: bypasses Player.aiStep()'s input→xxa/zza pipeline
    private float moveForward = 0.0f;
    private float moveStrafe = 0.0f;
    private boolean moveJumping = false;
    private boolean hasMovementOverride = false;

    // Track whether aiStep was called during super.tick()
    private boolean aiStepCalledThisTick = false;

    // Progressive digging state (real player mining simulation)
    private boolean isDigging = false;
    private BlockPos diggingPos = null;
    private Direction diggingFace = null;
    private int diggingTicksElapsed = 0;
    private BiConsumer<Boolean, String> diggingCallback = null;

    public FakePlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
        this.botName = profile.getName();
    }

    /**
     * Set movement inputs for the next tick.
     * @param forward 1.0 = forward, -1.0 = backward, 0 = stop
     * @param strafe  1.0 = left, -1.0 = right, 0 = none
     * @param jumping true = try to jump
     */
    public void setMovementInput(float forward, float strafe, boolean jumping) {
        this.moveForward = forward;
        this.moveStrafe = strafe;
        this.moveJumping = jumping;
        this.hasMovementOverride = true;
    }

    /**
     * Clear movement inputs (stop moving).
     */
    public void clearMovementInput() {
        this.moveForward = 0.0f;
        this.moveStrafe = 0.0f;
        this.moveJumping = false;
        this.hasMovementOverride = false;
    }

    /**
     * Override travel() to inject our movement vector.
     * Player.aiStep() reads from this.input (which is empty for fake players)
     * and sets xxa/zza to 0. Then it calls LivingEntity.aiStep() → travel(xxa, yya, zza).
     * We intercept here and replace with our desired values.
     *
     * Water handling: In water, onGround() is false so normal jump doesn't work.
     * Instead, apply upward velocity to swim toward the surface / maintain height.
     */
    @Override
    public void travel(Vec3 travelVector) {
        if (hasMovementOverride) {
            if (this.isInWater()) {
                // In water: always push up to maintain surface position.
                // Simulates holding jump — required because FakePlayer has no client Input system.
                Vec3 delta = this.getDeltaMovement();
                this.setDeltaMovement(delta.x, Math.max(delta.y, 0.04), delta.z);
            } else if (this.isInLava()) {
                // In lava: similar upward push
                Vec3 delta = this.getDeltaMovement();
                this.setDeltaMovement(delta.x, Math.max(delta.y, 0.04), delta.z);
            } else {
                // On land: normal jump
                if (moveJumping && this.onGround()) {
                    this.jumpFromGround();
                }
            }
            super.travel(new Vec3(moveStrafe, travelVector.y, moveForward));
        } else {
            // No active movement — but tread water to prevent sinking between pathfinding ticks
            if (this.isInWater()) {
                Vec3 delta = this.getDeltaMovement();
                this.setDeltaMovement(delta.x, Math.max(delta.y, 0.02), delta.z);
            }
            super.travel(travelVector);
        }
    }

    /**
     * Spawn this fake player into the world.
     * Must be called on the server thread.
     */
    public void spawnInWorld(double x, double y, double z) {
        this.moveTo(x, y, z, 0.0f, 0.0f);
        this.setGameMode(GameType.SURVIVAL);

        // Create fake connection and register with PlayerList
        FakeConnection fakeConn = new FakeConnection();
        this.server.getPlayerList().placeNewPlayer(fakeConn, this);

        // Ensure correct position after placeNewPlayer (which may teleport to spawn)
        this.teleportTo(this.serverLevel(), x, y, z, 0.0f, 0.0f);

        // Broadcast position to all clients
        this.serverLevel().getChunkSource().move(this);
        broadcastPositionToClients();

        BridgeMod.LOGGER.info("Bot '{}' spawned at ({}, {}, {})", botName, x, y, z);
    }

    /**
     * Remove this fake player from the world.
     * Must be called on the server thread.
     */
    public void despawn() {
        this.server.getPlayerList().remove(this);
        BridgeMod.LOGGER.info("Bot '{}' despawned", botName);
    }

    @Override
    public void aiStep() {
        aiStepCalledThisTick = true;
        super.aiStep();
    }

    @Override
    public void tick() {
        aiStepCalledThisTick = false;

        // Entity.baseTick() MUST run every tick — it updates critical state like
        // isInWater(), fire, air supply, etc. If ServerPlayer.tick() NPEs before
        // reaching Entity.tick(), these states never update (e.g. bot ignores water).
        // Call it explicitly first, then let super.tick() re-run it (baseTick is idempotent).
        try {
            this.baseTick();
        } catch (Exception ignored) {
        }

        try {
            super.tick();
        } catch (NullPointerException e) {
            BridgeMod.LOGGER.error("FakePlayer '{}' tick NPE: {}", botName, e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown", e);
        }

        // ServerPlayer.tick() doesn't run the LivingEntity physics chain
        // (real players get physics from client packets, not server tick).
        // If aiStep wasn't called, run it manually so travel() processes gravity+movement.
        if (!aiStepCalledThisTick) {
            this.aiStep();
        }

        // Progressive digging: gameMode.tick() (called in super.tick()) handles crack animation.
        // We track progress and send STOP_DESTROY_BLOCK when it's time to actually break.
        tickDigging();

        // Reset position tracking AFTER movement so the listener accepts the new position.
        if (this.connection != null) {
            try {
                this.connection.resetPosition();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Broadcast current position and head rotation to all nearby players.
     */
    public void broadcastPositionToClients() {
        this.serverLevel().getChunkSource().broadcastAndSend(this,
                new ClientboundRotateHeadPacket(this, (byte) (this.getYHeadRot() * 256.0F / 360.0F)));
        this.serverLevel().getChunkSource().broadcastAndSend(this,
                new ClientboundTeleportEntityPacket(this));
    }

    public String getBotName() {
        return botName;
    }

    /**
     * Must return true so LivingEntity.travel() processes physics (gravity + movement).
     * Default ServerPlayer returns false because real players are controlled by client packets.
     * Our fake player has no client — we control it directly on the server.
     */
    @Override
    public boolean isControlledByLocalInstance() {
        return true;
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    // ==================== Progressive Digging (Real Player Mining) ====================

    /**
     * Start progressive digging at the given position (like a real player holding left-click).
     * Uses ServerPlayerGameMode.handleBlockBreakAction() — the exact same server code path
     * as real clients. Produces crack animations, respects tool speed, and takes realistic time.
     *
     * @param pos      block to dig
     * @param face     which face to dig from (null = auto-detect from bot position)
     * @param callback called when digging completes: (success, reason)
     */
    public void startDigging(BlockPos pos, @Nullable Direction face, @Nullable BiConsumer<Boolean, String> callback) {
        // Abort any existing dig first
        if (isDigging) {
            abortDigging();
        }

        BlockState state = this.serverLevel().getBlockState(pos);
        if (state.isAir()) {
            if (callback != null) callback.accept(false, "Block is air");
            return;
        }

        // Select best tool and look at the block
        selectBestTool(state);
        lookAt(pos);

        // Auto-detect face if not specified
        if (face == null) {
            face = computeBestFace(pos);
        }

        // Send START_DESTROY_BLOCK — the same packet action a real client sends
        this.gameMode.handleBlockBreakAction(
                pos,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                face,
                this.serverLevel().getMaxBuildHeight(),
                0 // sequence (not needed for server-side)
        );

        // Check if the block was instantly mined (getDestroyProgress >= 1.0)
        if (this.serverLevel().getBlockState(pos).isAir()) {
            if (callback != null) callback.accept(true, "instant");
            return;
        }

        // Not instant — start progressive mining
        this.isDigging = true;
        this.diggingPos = pos.immutable();
        this.diggingFace = face;
        this.diggingTicksElapsed = 0;
        this.diggingCallback = callback;

        this.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Abort current digging. The block reverts to undamaged state.
     */
    public void abortDigging() {
        if (!isDigging || diggingPos == null) return;

        this.gameMode.handleBlockBreakAction(
                diggingPos,
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                diggingFace,
                this.serverLevel().getMaxBuildHeight(),
                0
        );

        BiConsumer<Boolean, String> cb = this.diggingCallback;
        resetDiggingState();
        if (cb != null) cb.accept(false, "aborted");
    }

    /**
     * Whether the bot is currently digging a block.
     */
    public boolean isDigging() {
        return isDigging;
    }

    /**
     * Tick the progressive digging state machine.
     * Called every tick from tick(), AFTER super.tick() (which runs gameMode.tick() for crack animation).
     */
    private void tickDigging() {
        if (!isDigging || diggingPos == null) return;

        BlockState state = this.serverLevel().getBlockState(diggingPos);

        // Block already gone (broken by something else, or explosion, etc.)
        if (state.isAir()) {
            BiConsumer<Boolean, String> cb = this.diggingCallback;
            resetDiggingState();
            if (cb != null) cb.accept(true, "destroyed");
            return;
        }

        // Swing arm for visual effect
        this.swing(InteractionHand.MAIN_HAND);
        // Keep looking at the block
        lookAt(diggingPos);

        // Calculate progress: getDestroyProgress() returns per-tick progress fraction
        diggingTicksElapsed++;
        float progressPerTick = state.getDestroyProgress(this, this.serverLevel(), diggingPos);
        float totalProgress = progressPerTick * (float)(diggingTicksElapsed + 1);

        if (totalProgress >= 1.0f) {
            // Mining complete — send STOP_DESTROY_BLOCK to actually break the block
            this.gameMode.handleBlockBreakAction(
                    diggingPos,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    diggingFace,
                    this.serverLevel().getMaxBuildHeight(),
                    0
            );

            boolean broken = this.serverLevel().getBlockState(diggingPos).isAir();
            BiConsumer<Boolean, String> cb = this.diggingCallback;
            resetDiggingState();
            if (cb != null) cb.accept(broken, broken ? "destroyed" : "failed");
        }
    }

    private void resetDiggingState() {
        this.isDigging = false;
        this.diggingPos = null;
        this.diggingFace = null;
        this.diggingTicksElapsed = 0;
        this.diggingCallback = null;
    }

    /**
     * Compute the best face to dig from, based on the bot's position relative to the block.
     */
    private Direction computeBestFace(BlockPos target) {
        Vec3 botPos = this.position();
        Vec3 blockCenter = Vec3.atCenterOf(target);
        Vec3 diff = botPos.subtract(blockCenter);

        double absX = Math.abs(diff.x);
        double absY = Math.abs(diff.y);
        double absZ = Math.abs(diff.z);

        if (absY > absX && absY > absZ) {
            return diff.y > 0 ? Direction.UP : Direction.DOWN;
        } else if (absX > absZ) {
            return diff.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return diff.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    /**
     * Make the bot look at the center of a block position.
     */
    private void lookAt(BlockPos target) {
        Vec3 blockCenter = Vec3.atCenterOf(target);
        double dx = blockCenter.x - this.getX();
        double dy = blockCenter.y - this.getEyeY();
        double dz = blockCenter.z - this.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float)(Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float pitch = (float)(Math.atan2(-dy, dist) * 180.0 / Math.PI);
        this.setYRot(yaw);
        this.setXRot(pitch);
    }

    // ==================== Phase 3C Step 1: Action APIs ====================

    /**
     * Instantly destroy a block at the given position.
     * Automatically selects the best tool from the hotbar first.
     * Uses ServerPlayerGameMode.destroyBlock() which handles drops, tool durability, and events.
     */
    public boolean breakBlock(BlockPos pos) {
        BlockState state = this.serverLevel().getBlockState(pos);
        if (state.isAir()) return false;
        selectBestTool(state);
        return this.gameMode.destroyBlock(pos);
    }

    /**
     * Place the currently held block against the given reference block face.
     * The new block will appear at against.relative(face).
     *
     * @param against the existing block to click on
     * @param face    the face of the reference block to place against
     * @return true if placement succeeded
     */
    public boolean placeBlock(BlockPos against, Direction face) {
        ItemStack stack = this.getMainHandItem();
        if (stack.isEmpty()) return false;

        // Hit point: center of the target face
        Vec3 hitVec = Vec3.atCenterOf(against).add(
                face.getStepX() * 0.5,
                face.getStepY() * 0.5,
                face.getStepZ() * 0.5
        );
        BlockHitResult hitResult = new BlockHitResult(hitVec, face, against, false);
        InteractionResult result = this.gameMode.useItemOn(
                this, this.serverLevel(), stack, InteractionHand.MAIN_HAND, hitResult
        );
        return result.consumesAction();
    }

    /**
     * Select the fastest tool in hotbar (slots 0-8) for the given block state.
     * Only switches if a tool is faster than bare hand (speed > 1.0).
     */
    public void selectBestTool(BlockState state) {
        float bestSpeed = 1.0f; // bare hand baseline
        int bestSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = this.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot >= 0) {
            this.getInventory().selected = bestSlot;
        }
    }

    /**
     * Find a hotbar slot containing a placeable full solid block.
     * Excludes FallingBlock types (sand, gravel, concrete_powder).
     *
     * @return hotbar slot index (0-8), or -1 if none found
     */
    public int findThrowawaySlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = this.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof BlockItem blockItem)) continue;
            if (blockItem.getBlock() instanceof FallingBlock) continue;
            // Must be a full opaque block (not slab, stairs, torch, etc.)
            if (blockItem.getBlock().defaultBlockState().canOcclude()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if the hotbar has any placeable solid block.
     */
    public boolean hasThrowawayBlock() {
        return findThrowawaySlot() >= 0;
    }

    /**
     * Switch to the first throwaway block slot in the hotbar.
     */
    public void equipThrowaway() {
        int slot = findThrowawaySlot();
        if (slot >= 0) {
            this.getInventory().selected = slot;
        }
    }

    /**
     * Switch the hotbar selected slot.
     * @param slot hotbar index 0-8
     */
    public void equipToMainHand(int slot) {
        if (slot >= 0 && slot < 9) {
            this.getInventory().selected = slot;
        }
    }
}
