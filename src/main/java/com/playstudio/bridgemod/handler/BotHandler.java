package com.playstudio.bridgemod.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.playstudio.bridgemod.BridgeMod;
import com.playstudio.bridgemod.bot.BotController;
import com.playstudio.bridgemod.bot.BotManager;
import com.playstudio.bridgemod.bot.FakePlayer;
import com.playstudio.bridgemod.websocket.BridgeWebSocketServer;
import com.playstudio.bridgemod.websocket.MessageHandler;
import com.playstudio.bridgemod.websocket.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.java_websocket.WebSocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all bot-related WebSocket commands and tick updates.
 * Registered both as a MessageHandler action handler and as a Forge event listener.
 */
public class BotHandler {

    private final BridgeWebSocketServer server;
    private final BotManager botManager;
    private final Map<String, BotController> controllers = new ConcurrentHashMap<>();

    public BotHandler(BridgeWebSocketServer server) {
        this.server = server;
        this.botManager = new BotManager();
    }

    /**
     * Register all bot commands with the message handler.
     */
    public void registerAll(MessageHandler messageHandler) {
        messageHandler.registerHandler("bot_spawn", this::handleSpawn);
        messageHandler.registerHandler("bot_despawn", this::handleDespawn);
        messageHandler.registerHandler("bot_list", this::handleList);
        messageHandler.registerHandler("bot_goto", this::handleGoto);
        messageHandler.registerHandler("bot_stop", this::handleStop);
    }

    /**
     * Tick all bot controllers. Called on server tick.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        for (BotController controller : controllers.values()) {
            try {
                controller.tick();
            } catch (Exception e) {
                BridgeMod.LOGGER.error("Error ticking bot '{}': {}",
                        controller.getBot().getBotName(), e.getMessage());
            }
        }
    }

    /**
     * Clean up all bots (called on server/mod shutdown).
     */
    public void shutdown() {
        controllers.clear();
        botManager.despawnAll();
    }

    public BotManager getBotManager() {
        return botManager;
    }

    /** Expose controllers for path rendering. */
    public Map<String, BotController> getControllers() {
        return controllers;
    }

    // --- Helper: get server instance ---

    private MinecraftServer getServer() {
        return Minecraft.getInstance().getSingleplayerServer();
    }

    // --- Command Handlers ---

    /**
     * bot_spawn: Create a new bot player.
     * params: {name, x?, y?, z?}
     */
    private void handleSpawn(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name")) {
            server.sendResponse(conn, id, false, null, "Missing 'name' parameter");
            return;
        }
        String name = params.get("name").getAsString();

        // Get spawn position from params, or default to near the player
        Double x = params.has("x") ? params.get("x").getAsDouble() : null;
        Double y = params.has("y") ? params.get("y").getAsDouble() : null;
        Double z = params.has("z") ? params.get("z").getAsDouble() : null;

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available (single-player only for now)");
            return;
        }

        mcServer.execute(() -> {
            try {
                // Default position: near the local player
                double spawnX, spawnY, spawnZ;
                if (x != null && y != null && z != null) {
                    spawnX = x;
                    spawnY = y;
                    spawnZ = z;
                } else {
                    LocalPlayer localPlayer = Minecraft.getInstance().player;
                    if (localPlayer != null) {
                        spawnX = localPlayer.getX() + 2;
                        spawnY = localPlayer.getY();
                        spawnZ = localPlayer.getZ() + 2;
                    } else {
                        var spawnPos = mcServer.overworld().getSharedSpawnPos();
                        spawnX = spawnPos.getX() + 0.5;
                        spawnY = spawnPos.getY();
                        spawnZ = spawnPos.getZ() + 0.5;
                    }
                }

                FakePlayer bot = botManager.spawnBot(mcServer, name, spawnX, spawnY, spawnZ);
                if (bot == null) {
                    server.sendResponse(conn, id, false, null,
                            "Failed to spawn bot '" + name + "' (name conflict or player online)");
                    return;
                }

                // Create controller for this bot
                controllers.put(name, new BotController(bot));

                JsonObject data = new JsonObject();
                data.addProperty("botName", name);
                data.add("position", Protocol.vec3(bot.getX(), bot.getY(), bot.getZ()));
                server.sendResponse(conn, id, true, data, null);

            } catch (Exception e) {
                BridgeMod.LOGGER.error("Error spawning bot '{}': {}", name, e.getMessage(), e);
                server.sendResponse(conn, id, false, null, "Spawn error: " + e.getMessage());
            }
        });
    }

    /**
     * bot_despawn: Remove a bot player.
     * params: {name}
     */
    private void handleDespawn(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name")) {
            server.sendResponse(conn, id, false, null, "Missing 'name' parameter");
            return;
        }
        String name = params.get("name").getAsString();

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }

        mcServer.execute(() -> {
            // Stop controller first
            BotController controller = controllers.remove(name);
            if (controller != null) {
                controller.stop();
            }

            boolean removed = botManager.despawnBot(name);
            if (removed) {
                server.sendResponse(conn, id, true, null, null);
            } else {
                server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            }
        });
    }

    /**
     * bot_list: List all active bots.
     * params: {}
     */
    private void handleList(WebSocket conn, String id, JsonObject params) {
        JsonObject data = new JsonObject();
        JsonArray botsArray = new JsonArray();

        for (FakePlayer bot : botManager.getAllBots()) {
            JsonObject botObj = new JsonObject();
            botObj.addProperty("name", bot.getBotName());
            botObj.add("position", Protocol.vec3(bot.getX(), bot.getY(), bot.getZ()));
            botObj.addProperty("health", bot.getHealth());
            botObj.addProperty("food", bot.getFoodData().getFoodLevel());

            BotController controller = controllers.get(bot.getBotName());
            botObj.addProperty("navigating", controller != null && controller.isNavigating());

            botsArray.add(botObj);
        }

        data.add("bots", botsArray);
        data.addProperty("count", botManager.getBotCount());
        server.sendResponse(conn, id, true, data, null);
    }

    /**
     * bot_goto: Navigate bot to target position.
     * params: {name, x, y, z, range?}
     * This is a blocking command - response is sent when navigation completes.
     */
    private void handleGoto(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name") || !params.has("x") || !params.has("y") || !params.has("z")) {
            server.sendResponse(conn, id, false, null, "Missing required params (name, x, y, z)");
            return;
        }

        String name = params.get("name").getAsString();
        double x = params.get("x").getAsDouble();
        double y = params.get("y").getAsDouble();
        double z = params.get("z").getAsDouble();
        int range = params.has("range") ? params.get("range").getAsInt() : 2;

        BotController controller = controllers.get(name);
        if (controller == null) {
            server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            return;
        }

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }

        mcServer.execute(() -> {
            // Stop any current navigation
            if (controller.isNavigating()) {
                controller.stop();
            }

            // Start new navigation with callback
            controller.startGoto(x, y, z, range, (success, reason) -> {
                JsonObject data = new JsonObject();
                data.addProperty("arrived", success);
                if (reason != null) {
                    data.addProperty("reason", reason);
                }
                data.add("position", Protocol.vec3(
                        controller.getBot().getX(),
                        controller.getBot().getY(),
                        controller.getBot().getZ()));
                server.sendResponse(conn, id, success, data, success ? null : reason);
            });
        });
    }

    /**
     * bot_stop: Stop bot's current navigation.
     * params: {name}
     */
    private void handleStop(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name")) {
            server.sendResponse(conn, id, false, null, "Missing 'name' parameter");
            return;
        }
        String name = params.get("name").getAsString();

        BotController controller = controllers.get(name);
        if (controller == null) {
            server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            return;
        }

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }

        mcServer.execute(() -> {
            controller.stop();
            server.sendResponse(conn, id, true, null, null);
        });
    }
}
