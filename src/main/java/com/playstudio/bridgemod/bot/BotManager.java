package com.playstudio.bridgemod.bot;

import com.mojang.authlib.GameProfile;
import com.playstudio.bridgemod.BridgeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of all fake bot players.
 * Thread-safe: can be queried from any thread, but mutations happen on server thread.
 */
public class BotManager {

    private final Map<String, FakePlayer> bots = new ConcurrentHashMap<>();

    /**
     * Spawn a new bot player at the specified position.
     * Must be called on the server thread.
     *
     * @param server the Minecraft server
     * @param name   the bot's display name
     * @param x      spawn X
     * @param y      spawn Y
     * @param z      spawn Z
     * @return the spawned FakePlayer, or null if a bot with that name already exists
     */
    public FakePlayer spawnBot(MinecraftServer server, String name, double x, double y, double z) {
        if (bots.containsKey(name)) {
            BridgeMod.LOGGER.warn("Bot '{}' already exists", name);
            return null;
        }

        // Check if a real player with this name is online
        if (server.getPlayerList().getPlayerByName(name) != null) {
            BridgeMod.LOGGER.warn("A real player named '{}' is online, cannot create bot", name);
            return null;
        }

        ServerLevel level = server.overworld();

        // Create offline-mode UUID from name
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
        GameProfile profile = new GameProfile(uuid, name);

        FakePlayer bot = new FakePlayer(server, level, profile);
        bot.spawnInWorld(x, y, z);

        bots.put(name, bot);
        return bot;
    }

    /**
     * Despawn and remove a bot by name.
     * Must be called on the server thread.
     *
     * @return true if the bot existed and was removed
     */
    public boolean despawnBot(String name) {
        FakePlayer bot = bots.remove(name);
        if (bot == null) {
            return false;
        }
        bot.despawn();
        return true;
    }

    /**
     * Despawn all bots. Called on server shutdown.
     */
    public void despawnAll() {
        for (FakePlayer bot : bots.values()) {
            try {
                bot.despawn();
            } catch (Exception e) {
                BridgeMod.LOGGER.warn("Error despawning bot '{}': {}", bot.getBotName(), e.getMessage());
            }
        }
        bots.clear();
    }

    /**
     * Get a bot by name.
     */
    public FakePlayer getBot(String name) {
        return bots.get(name);
    }

    /**
     * Get all active bots.
     */
    public Collection<FakePlayer> getAllBots() {
        return Collections.unmodifiableCollection(bots.values());
    }

    /**
     * Check if a bot with this name exists.
     */
    public boolean hasBot(String name) {
        return bots.containsKey(name);
    }

    /**
     * Get the number of active bots.
     */
    public int getBotCount() {
        return bots.size();
    }
}
