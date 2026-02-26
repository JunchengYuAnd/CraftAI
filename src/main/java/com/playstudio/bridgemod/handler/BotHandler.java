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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
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
        messageHandler.registerHandler("bot_break", this::handleBreak);
        messageHandler.registerHandler("bot_dig", this::handleDig);
        messageHandler.registerHandler("bot_dig_abort", this::handleDigAbort);
        messageHandler.registerHandler("bot_place", this::handlePlace);
        messageHandler.registerHandler("bot_equip", this::handleEquip);
        messageHandler.registerHandler("bot_inventory", this::handleInventory);
        messageHandler.registerHandler("bot_activate", this::handleActivate);
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

    /**
     * bot_break: Break a block at the given position.
     * params: {name, x, y, z}
     */
    private void handleBreak(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name") || !params.has("x") || !params.has("y") || !params.has("z")) {
            server.sendResponse(conn, id, false, null, "Missing required params (name, x, y, z)");
            return;
        }

        String name = params.get("name").getAsString();
        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();

        FakePlayer bot = botManager.getBot(name);
        if (bot == null) {
            server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            return;
        }

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }

        mcServer.execute(() -> {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState before = bot.serverLevel().getBlockState(pos);
            boolean success = bot.breakBlock(pos);
            BlockState after = bot.serverLevel().getBlockState(pos);

            JsonObject data = new JsonObject();
            data.addProperty("blockBefore", before.getBlock().getName().getString());
            data.addProperty("blockAfter", after.getBlock().getName().getString());
            server.sendResponse(conn, id, success, data, success ? null : "Failed to break block");
        });
    }

    /**
     * bot_dig: Progressive block mining (like a real player holding left-click).
     * params: {name, x, y, z, face?}
     * Response is sent when digging completes (async, like bot_goto).
     */
    private void handleDig(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name") || !params.has("x") || !params.has("y") || !params.has("z")) {
            server.sendResponse(conn, id, false, null, "Missing required params (name, x, y, z)");
            return;
        }

        String name = params.get("name").getAsString();
        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();

        // Parse optional face
        Direction face = null;
        if (params.has("face")) {
            String faceStr = params.get("face").getAsString().toLowerCase();
            switch (faceStr) {
                case "up":    face = Direction.UP; break;
                case "down":  face = Direction.DOWN; break;
                case "north": face = Direction.NORTH; break;
                case "south": face = Direction.SOUTH; break;
                case "east":  face = Direction.EAST; break;
                case "west":  face = Direction.WEST; break;
                default:
                    server.sendResponse(conn, id, false, null, "Invalid face: " + faceStr);
                    return;
            }
        }

        FakePlayer bot = botManager.getBot(name);
        if (bot == null) {
            server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            return;
        }

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }

        Direction finalFace = face;
        mcServer.execute(() -> {
            BlockPos pos = new BlockPos(x, y, z);
            String blockType = bot.serverLevel().getBlockState(pos).getBlock().getName().getString();

            bot.startDigging(pos, finalFace, (success, reason) -> {
                JsonObject data = new JsonObject();
                data.addProperty("blockType", blockType);
                data.addProperty("reason", reason != null ? reason : "");
                data.add("position", Protocol.vec3(x, y, z));
                server.sendResponse(conn, id, success, data, success ? null : reason);
            });
        });
    }

    /**
     * bot_dig_abort: Cancel the bot's current digging operation.
     * params: {name}
     */
    private void handleDigAbort(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name")) {
            server.sendResponse(conn, id, false, null, "Missing 'name' parameter");
            return;
        }

        String name = params.get("name").getAsString();
        FakePlayer bot = botManager.getBot(name);
        if (bot == null) {
            server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            return;
        }

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }

        mcServer.execute(() -> {
            boolean wasDigging = bot.isDigging();
            bot.abortDigging();

            JsonObject data = new JsonObject();
            data.addProperty("wasDigging", wasDigging);
            server.sendResponse(conn, id, true, data, null);
        });
    }

    /**
     * bot_place: Place a block from the bot's hand.
     * params: {name, x, y, z, face}
     * x,y,z = the reference block to click on; face = which face ("up","down","north","south","east","west")
     */
    private void handlePlace(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name") || !params.has("x") || !params.has("y") || !params.has("z")) {
            server.sendResponse(conn, id, false, null, "Missing required params (name, x, y, z)");
            return;
        }

        String name = params.get("name").getAsString();
        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();
        String faceStr = params.has("face") ? params.get("face").getAsString() : "up";

        Direction face;
        switch (faceStr.toLowerCase()) {
            case "up":    face = Direction.UP; break;
            case "down":  face = Direction.DOWN; break;
            case "north": face = Direction.NORTH; break;
            case "south": face = Direction.SOUTH; break;
            case "east":  face = Direction.EAST; break;
            case "west":  face = Direction.WEST; break;
            default:
                server.sendResponse(conn, id, false, null, "Invalid face: " + faceStr);
                return;
        }

        FakePlayer bot = botManager.getBot(name);
        if (bot == null) {
            server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            return;
        }

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }

        mcServer.execute(() -> {
            BlockPos against = new BlockPos(x, y, z);
            boolean success = bot.placeBlock(against, face);

            JsonObject data = new JsonObject();
            BlockPos placed = against.relative(face);
            data.addProperty("placedBlock", bot.serverLevel().getBlockState(placed).getBlock().getName().getString());
            server.sendResponse(conn, id, success, data, success ? null : "Failed to place block");
        });
    }

    /**
     * bot_equip: Switch bot's hotbar slot.
     * params: {name, slot} — slot 0-8
     */
    private void handleEquip(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name")) {
            server.sendResponse(conn, id, false, null, "Missing 'name' parameter");
            return;
        }

        String name = params.get("name").getAsString();
        FakePlayer bot = botManager.getBot(name);
        if (bot == null) {
            server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            return;
        }

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }

        mcServer.execute(() -> {
            if (params.has("slot")) {
                bot.equipToMainHand(params.get("slot").getAsInt());
            } else if (params.has("throwaway") && params.get("throwaway").getAsBoolean()) {
                bot.equipThrowaway();
            }

            JsonObject data = new JsonObject();
            data.addProperty("selectedSlot", bot.getInventory().selected);
            String heldItem = bot.getMainHandItem().isEmpty() ? "empty"
                    : bot.getMainHandItem().getItem().getName(bot.getMainHandItem()).getString();
            data.addProperty("heldItem", heldItem);
            data.addProperty("hasThrowaway", bot.hasThrowawayBlock());
            server.sendResponse(conn, id, true, data, null);
        });
    }

    /**
     * bot_inventory: Query a bot's inventory contents.
     * params: {name}
     */
    private void handleInventory(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name")) {
            server.sendResponse(conn, id, false, null, "Missing 'name' parameter");
            return;
        }

        String name = params.get("name").getAsString();
        FakePlayer bot = botManager.getBot(name);
        if (bot == null) {
            server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            return;
        }

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }

        mcServer.execute(() -> {
            JsonObject data = new JsonObject();
            JsonArray items = new JsonArray();
            int emptyCount = 0;

            var menu = bot.inventoryMenu;
            for (int i = 0; i < menu.slots.size(); i++) {
                ItemStack stack = menu.slots.get(i).getItem();
                if (stack.isEmpty()) {
                    if (i >= 9 && i <= 44) emptyCount++;
                    continue;
                }

                JsonObject itemObj = new JsonObject();
                itemObj.addProperty("slot", i);
                String itemName = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                itemObj.addProperty("name", itemName);
                itemObj.addProperty("count", stack.getCount());

                if (stack.isDamageableItem()) {
                    int maxDur = stack.getMaxDamage();
                    int currentDur = maxDur - stack.getDamageValue();
                    itemObj.addProperty("durability", currentDur);
                    itemObj.addProperty("maxDurability", maxDur);
                }

                items.add(itemObj);
            }

            data.add("items", items);
            data.addProperty("emptySlotCount", emptyCount);
            data.addProperty("selectedSlot", bot.getInventory().selected);

            String heldItem = bot.getMainHandItem().isEmpty() ? "empty"
                    : BuiltInRegistries.ITEM.getKey(bot.getMainHandItem().getItem()).getPath();
            data.addProperty("heldItem", heldItem);

            server.sendResponse(conn, id, true, data, null);
        });
    }

    // ==================== Phase 4: Interaction Commands ====================

    /**
     * bot_activate: Right-click interact with a block (open door/chest, press button, pull lever).
     * params: {name, x, y, z, face?}
     */
    private void handleActivate(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name") || !params.has("x") || !params.has("y") || !params.has("z")) {
            server.sendResponse(conn, id, false, null, "Missing required params (name, x, y, z)");
            return;
        }

        String name = params.get("name").getAsString();
        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();

        Direction face = null;
        if (params.has("face")) {
            face = parseDirection(params.get("face").getAsString());
            if (face == null) {
                server.sendResponse(conn, id, false, null, "Invalid face: " + params.get("face").getAsString());
                return;
            }
        }

        FakePlayer bot = botManager.getBot(name);
        if (bot == null) {
            server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            return;
        }

        BotController controller = controllers.get(name);
        if (controller == null) {
            server.sendResponse(conn, id, false, null, "No controller for bot '" + name + "'");
            return;
        }

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }

        Direction finalFace = face;
        mcServer.execute(() -> {
            BlockPos pos = new BlockPos(x, y, z);
            double dist = bot.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));

            if (dist <= 4.5) {
                // Close enough — activate immediately
                doActivateAndRespond(conn, id, bot, pos, finalFace);
            } else {
                // Too far — pathfind to the block first, then activate on arrival
                if (controller.isNavigating()) {
                    controller.stop();
                }
                controller.startGoto(x, y, z, 2, (success, reason) -> {
                    if (success) {
                        doActivateAndRespond(conn, id, bot, pos, finalFace);
                    } else {
                        server.sendResponse(conn, id, false, null,
                                "Failed to reach block: " + reason);
                    }
                });
            }
        });
    }

    /** Perform activation and send response. */
    private void doActivateAndRespond(WebSocket conn, String id, FakePlayer bot, BlockPos pos, Direction face) {
        String blockName = BuiltInRegistries.BLOCK.getKey(bot.serverLevel().getBlockState(pos).getBlock()).getPath();
        net.minecraft.world.InteractionResult result = bot.activateBlock(pos, face);

        JsonObject data = new JsonObject();
        data.addProperty("result", result.name());
        data.addProperty("blockName", blockName);
        data.add("position", Protocol.vec3(pos.getX(), pos.getY(), pos.getZ()));
        server.sendResponse(conn, id, result.consumesAction(), data, null);
    }

    // --- Helper: parse direction string ---
    private static Direction parseDirection(String s) {
        if (s == null) return null;
        switch (s.toLowerCase()) {
            case "up":    return Direction.UP;
            case "down":  return Direction.DOWN;
            case "north": return Direction.NORTH;
            case "south": return Direction.SOUTH;
            case "east":  return Direction.EAST;
            case "west":  return Direction.WEST;
            default:      return null;
        }
    }
}
