package com.playstudio.bridgemod.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.playstudio.bridgemod.BridgeMod;
import com.playstudio.bridgemod.bot.BotController;
import com.playstudio.bridgemod.bot.BotManager;
import com.playstudio.bridgemod.bot.CombatConfig;
import com.playstudio.bridgemod.bot.CombatController;
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
    private final Map<String, CombatController> combatControllers = new ConcurrentHashMap<>();

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
        messageHandler.registerHandler("bot_attack", this::handleAttack);
        messageHandler.registerHandler("bot_attack_nearby", this::handleAttackNearby);
        messageHandler.registerHandler("bot_attack_cancel", this::handleAttackCancel);
    }

    /**
     * Tick all bot controllers. Called on server tick.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        for (Map.Entry<String, BotController> entry : controllers.entrySet()) {
            try {
                String name = entry.getKey();

                // Tick combat controller first (state machine, attack decisions, re-path)
                CombatController combat = combatControllers.get(name);
                if (combat != null) {
                    combat.tick();
                }

                // Tick navigation controller (path execution)
                // During PURSUING: BotController executes the path set by CombatController
                // During MELEE: BotController.navigating=false → tick() no-ops
                entry.getValue().tick();
            } catch (Exception e) {
                BridgeMod.LOGGER.error("Error ticking bot '{}': {}",
                        entry.getValue().getBot().getBotName(), e.getMessage());
            }
        }
    }

    /**
     * Clean up all bots (called on server/mod shutdown).
     */
    public void shutdown() {
        combatControllers.values().forEach(CombatController::stop);
        combatControllers.clear();
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

                // Create controllers for this bot
                BotController navCtrl = new BotController(bot);
                controllers.put(name, navCtrl);
                combatControllers.put(name, new CombatController(bot, navCtrl));

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
            // Stop controllers first
            CombatController combat = combatControllers.remove(name);
            if (combat != null) {
                combat.stop();
            }
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
            // Stop combat if active
            CombatController combat = combatControllers.get(name);
            if (combat != null) {
                combat.stop();
            }
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

    /**
     * bot_attack: Start melee combat with a target entity.
     * params: {name, entityId}
     * Async: response sent when combat ends (target dead, escaped, cancelled).
     */
    private void handleAttack(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name") || !params.has("entityId")) {
            server.sendResponse(conn, id, false, null, "Missing required params (name, entityId)");
            return;
        }

        String name = params.get("name").getAsString();
        int entityId = params.get("entityId").getAsInt();

        CombatController combat = combatControllers.get(name);
        if (combat == null) {
            server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            return;
        }

        BotController navCtrl = controllers.get(name);
        FakePlayer bot = botManager.getBot(name);

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }

        // Parse optional combat config
        final CombatConfig finalConfig = parseCombatConfig(params);
        mcServer.execute(() -> {
            // Stop any current navigation or combat
            if (navCtrl != null && navCtrl.isNavigating()) {
                navCtrl.stop();
            }

            combat.startAttack(entityId, finalConfig, (success, reason) -> {
                JsonObject data = new JsonObject();
                data.addProperty("targetDead", success);
                data.addProperty("reason", reason != null ? reason : "");
                if (bot != null) {
                    data.add("position", Protocol.vec3(bot.getX(), bot.getY(), bot.getZ()));
                }
                server.sendResponse(conn, id, success, data, success ? null : reason);
            });
        });
    }

    /**
     * bot_attack_cancel: Stop the bot's current combat.
     * params: {name}
     */
    private void handleAttackCancel(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name")) {
            server.sendResponse(conn, id, false, null, "Missing 'name' parameter");
            return;
        }

        String name = params.get("name").getAsString();
        CombatController combat = combatControllers.get(name);
        if (combat == null) {
            server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            return;
        }

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }

        mcServer.execute(() -> {
            boolean wasActive = combat.isActive();
            combat.stop();
            JsonObject data = new JsonObject();
            data.addProperty("wasFighting", wasActive);
            server.sendResponse(conn, id, true, data, null);
        });
    }

    /**
     * bot_attack_nearby: Auto-attack all hostile mobs within radius.
     * Async: response sent when all hostiles dead or cancelled.
     * params: {name, radius?, mode?, strafe?, shieldBreaking?, autoShield?}
     */
    private void handleAttackNearby(WebSocket conn, String id, JsonObject params) {
        if (!params.has("name")) {
            server.sendResponse(conn, id, false, null, "Missing 'name' parameter");
            return;
        }

        String name = params.get("name").getAsString();
        double radius = params.has("radius") ? params.get("radius").getAsDouble() : 32.0;

        CombatController combat = combatControllers.get(name);
        if (combat == null) {
            server.sendResponse(conn, id, false, null, "No bot named '" + name + "'");
            return;
        }

        BotController navCtrl = controllers.get(name);
        FakePlayer bot = botManager.getBot(name);

        // Parse combat config
        final CombatConfig finalConfig = parseCombatConfig(params);

        MinecraftServer mcServer = getServer();
        if (mcServer == null) {
            server.sendResponse(conn, id, false, null, "No server available");
            return;
        }
        final double finalRadius = radius;
        mcServer.execute(() -> {
            if (navCtrl != null && navCtrl.isNavigating()) {
                navCtrl.stop();
            }

            combat.startAutoAttack(finalConfig, finalRadius, (success, reason) -> {
                JsonObject data = new JsonObject();
                // reason format: "all_clear:3" or "cancelled:2"
                String[] parts = reason.split(":");
                data.addProperty("reason", parts[0]);
                int kills = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                data.addProperty("killCount", kills);
                if (bot != null) {
                    data.add("position", Protocol.vec3(bot.getX(), bot.getY(), bot.getZ()));
                }
                server.sendResponse(conn, id, success, data, success ? null : parts[0]);
            });
        });
    }

    // --- Helper: parse CombatConfig from WebSocket params ---
    private static CombatConfig parseCombatConfig(JsonObject params) {
        CombatConfig config = new CombatConfig();
        if (params.has("mode")) {
            String mode = params.get("mode").getAsString().toLowerCase();
            switch (mode) {
                case "crit": config.attackMode = CombatConfig.AttackMode.CRIT; break;
                case "wtap": config.attackMode = CombatConfig.AttackMode.WTAP; break;
                case "stap": config.attackMode = CombatConfig.AttackMode.STAP; break;
                default: config.attackMode = CombatConfig.AttackMode.NORMAL; break;
            }
        }
        if (params.has("strafe")) {
            String strafe = params.get("strafe").getAsString().toLowerCase();
            switch (strafe) {
                case "circle": config.strafeMode = CombatConfig.StrafeMode.CIRCLE; break;
                case "random": config.strafeMode = CombatConfig.StrafeMode.RANDOM; break;
                case "intelligent": config.strafeMode = CombatConfig.StrafeMode.INTELLIGENT; break;
                default: config.strafeMode = CombatConfig.StrafeMode.NONE; break;
            }
        }
        if (params.has("strafeIntensity")) {
            config.strafeIntensity = params.get("strafeIntensity").getAsFloat();
        }
        if (params.has("strafeChangeInterval")) {
            config.strafeChangeIntervalTicks = params.get("strafeChangeInterval").getAsInt();
        }
        if (params.has("shieldBreaking")) {
            config.shieldBreaking = params.get("shieldBreaking").getAsBoolean();
        }
        if (params.has("autoShield")) {
            config.autoShield = params.get("autoShield").getAsBoolean();
        }
        if (params.has("kbCancel")) {
            String kbc = params.get("kbCancel").getAsString().toLowerCase();
            switch (kbc) {
                case "jump": config.kbCancelMode = CombatConfig.KBCancelMode.JUMP; break;
                case "shift": config.kbCancelMode = CombatConfig.KBCancelMode.SHIFT; break;
                default: config.kbCancelMode = CombatConfig.KBCancelMode.NONE; break;
            }
        }
        if (params.has("kbCancelShiftTicks")) {
            config.kbCancelShiftTicks = params.get("kbCancelShiftTicks").getAsInt();
        }
        if (params.has("reactionaryCrit")) {
            config.reactionaryCrit = params.get("reactionaryCrit").getAsBoolean();
        }
        if (params.has("stapBackTicks")) {
            config.stapBackTicks = params.get("stapBackTicks").getAsInt();
        }
        if (params.has("tooCloseRange")) {
            config.tooCloseRange = params.get("tooCloseRange").getAsFloat();
        }
        if (params.has("backoffOnHitTicks")) {
            config.backoffOnHitTicks = params.get("backoffOnHitTicks").getAsInt();
        }
        if (params.has("threatAwareness")) {
            config.threatAwareness = params.get("threatAwareness").getAsBoolean();
        }
        if (params.has("threatScanRadius")) {
            config.threatScanRadius = params.get("threatScanRadius").getAsDouble();
        }
        if (params.has("threatEvasionWeight")) {
            config.threatEvasionWeight = params.get("threatEvasionWeight").getAsFloat();
        }
        // Potential field movement (Phase 1)
        if (params.has("usePotentialFields")) {
            config.usePotentialFields = params.get("usePotentialFields").getAsBoolean();
        }
        if (params.has("optimalMeleeDistance")) {
            config.optimalMeleeDistance = params.get("optimalMeleeDistance").getAsDouble();
        }
        if (params.has("tangentStrength")) {
            config.tangentStrength = params.get("tangentStrength").getAsDouble();
        }
        if (params.has("threatRepulsionK")) {
            config.threatRepulsionK = params.get("threatRepulsionK").getAsDouble();
        }
        if (params.has("threatRepulsionRange")) {
            config.threatRepulsionRange = params.get("threatRepulsionRange").getAsDouble();
        }
        return config;
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
