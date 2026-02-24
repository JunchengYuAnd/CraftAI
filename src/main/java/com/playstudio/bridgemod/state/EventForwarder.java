package com.playstudio.bridgemod.state;

import com.google.gson.JsonObject;
import com.playstudio.bridgemod.BridgeMod;
import com.playstudio.bridgemod.websocket.BridgeWebSocketServer;
import com.playstudio.bridgemod.websocket.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Listens for Minecraft events and forwards them as WebSocket event messages.
 * Must be registered on MinecraftForge.EVENT_BUS.
 *
 * Phase 1 events: chat, messagestr, login, end, spawn, health, death
 */
public class EventForwarder {

    private final BridgeWebSocketServer server;

    // Health/death tracking (tick-based detection)
    private float lastHealth = 20.0f;
    private int lastFood = 20;
    private boolean wasAlive = true;
    private boolean playerTracked = false;

    public EventForwarder(BridgeWebSocketServer server) {
        this.server = server;
    }

    // --- Chat Events ---

    /**
     * Player chat messages (from other players).
     */
    @SubscribeEvent
    public void onPlayerChat(ClientChatReceivedEvent.Player event) {
        if (!server.hasHandshakedConnections()) return;

        Component message = event.getMessage();
        String text = message.getString();

        // Extract username from the bound chat type
        // The message format is typically "<username> message"
        String username = "unknown";
        try {
            // Try to get sender info from the chat type bound data
            if (event.getBoundChatType() != null && event.getBoundChatType().name() != null) {
                username = event.getBoundChatType().name().getString();
            }
        } catch (Exception e) {
            // Fallback: try to parse "<username> message" format
            if (text.startsWith("<") && text.contains(">")) {
                username = text.substring(1, text.indexOf(">"));
                text = text.substring(text.indexOf(">") + 2);
            }
        }

        JsonObject data = new JsonObject();
        data.addProperty("username", username);
        data.addProperty("message", text);
        broadcast("chat", data);
    }

    /**
     * System messages (death, join/leave, advancements, etc.).
     */
    @SubscribeEvent
    public void onSystemChat(ClientChatReceivedEvent.System event) {
        if (!server.hasHandshakedConnections()) return;
        if (event.isOverlay()) return; // skip action bar messages

        String text = event.getMessage().getString();

        JsonObject data = new JsonObject();
        data.addProperty("message", text);
        broadcast("messagestr", data);
    }

    // --- Player Network Events ---

    /**
     * Player logged in (joined server).
     */
    @SubscribeEvent
    public void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        LocalPlayer player = event.getPlayer();
        if (player != null) {
            lastHealth = player.getHealth();
            lastFood = player.getFoodData().getFoodLevel();
            wasAlive = true;
            playerTracked = true;
        }

        JsonObject data = new JsonObject();
        data.addProperty("version", "1.20.1");
        broadcast("login", data);

        BridgeMod.LOGGER.info("Player logged in, sending login event");
    }

    /**
     * Player logged out (disconnected from server).
     */
    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        playerTracked = false;

        JsonObject data = new JsonObject();
        data.addProperty("reason", "disconnect");
        broadcast("end", data);

        BridgeMod.LOGGER.info("Player logged out, sending end event");
    }

    /**
     * Player respawned or changed dimension.
     */
    @SubscribeEvent
    public void onClone(ClientPlayerNetworkEvent.Clone event) {
        LocalPlayer newPlayer = event.getNewPlayer();
        if (newPlayer != null) {
            lastHealth = newPlayer.getHealth();
            lastFood = newPlayer.getFoodData().getFoodLevel();
            wasAlive = true;
            playerTracked = true;
        }

        broadcast("spawn", new JsonObject());
        BridgeMod.LOGGER.info("Player respawned/dimension changed, sending spawn event");
    }

    // --- Tick-based Health & Death Detection ---

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!server.hasHandshakedConnections()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null) {
            playerTracked = false;
            return;
        }

        // Initialize tracking on first tick with player
        if (!playerTracked) {
            lastHealth = player.getHealth();
            lastFood = player.getFoodData().getFoodLevel();
            wasAlive = player.isAlive();
            playerTracked = true;
            return;
        }

        float currentHealth = player.getHealth();
        int currentFood = player.getFoodData().getFoodLevel();
        int currentOxygen = player.getAirSupply();

        // Health change event
        if (currentHealth != lastHealth || currentFood != lastFood) {
            JsonObject data = new JsonObject();
            data.addProperty("health", currentHealth);
            data.addProperty("food", currentFood);
            data.addProperty("oxygen", currentOxygen);
            broadcast("health", data);

            lastHealth = currentHealth;
            lastFood = currentFood;
        }

        // Death detection
        boolean isAlive = player.isAlive();
        if (wasAlive && !isAlive) {
            JsonObject data = new JsonObject();
            data.addProperty("message", "Player died");
            data.add("deathPos", Protocol.vec3(
                    player.getX(), player.getY(), player.getZ()));
            broadcast("death", data);
        }
        wasAlive = isAlive;
    }

    // --- Helper ---

    private void broadcast(String eventName, JsonObject data) {
        if (server != null && server.hasHandshakedConnections()) {
            server.broadcastToHandshaked(Protocol.event(eventName, data));
        }
    }
}
