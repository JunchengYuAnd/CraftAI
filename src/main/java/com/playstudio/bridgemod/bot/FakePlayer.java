package com.playstudio.bridgemod.bot;

import com.mojang.authlib.GameProfile;
import com.playstudio.bridgemod.BridgeMod;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

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
}
