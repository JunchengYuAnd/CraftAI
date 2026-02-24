package com.playstudio.bridgemod.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.playstudio.bridgemod.BridgeMod;
import com.playstudio.bridgemod.handler.ChatHandler;
import net.minecraft.client.Minecraft;
import org.java_websocket.WebSocket;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses incoming WebSocket JSON messages and routes them to the appropriate handler
 * based on the "action" field. Bridges WebSocket worker threads to the MC client thread.
 */
public class MessageHandler {

    @FunctionalInterface
    public interface ActionHandler {
        void handle(WebSocket conn, String id, JsonObject params);
    }

    private final BridgeWebSocketServer server;
    private final Map<String, ActionHandler> handlers = new HashMap<>();

    public MessageHandler(BridgeWebSocketServer server) {
        this.server = server;
        registerDefaultHandlers();
    }

    private void registerDefaultHandlers() {
        handlers.put("handshake", this::handleHandshake);
        handlers.put("chat", this::handleChat);
        handlers.put("whisper", this::handleWhisper);
    }

    /**
     * Register a custom action handler. Used by later phases to add commands.
     */
    public void registerHandler(String action, ActionHandler handler) {
        handlers.put(action, handler);
    }

    /**
     * Handle an incoming WebSocket message. Called on WebSocket worker thread.
     */
    public void handleMessage(WebSocket conn, String rawMessage) {
        try {
            JsonObject json = JsonParser.parseString(rawMessage).getAsJsonObject();

            String id = json.has("id") ? json.get("id").getAsString() : null;
            String action = json.has("action") ? json.get("action").getAsString() : null;
            JsonObject params = json.has("params") ? json.getAsJsonObject("params") : new JsonObject();

            if (action == null) {
                server.sendResponse(conn, id, false, null, "Missing 'action' field");
                return;
            }

            ActionHandler handler = handlers.get(action);
            if (handler == null) {
                server.sendResponse(conn, id, false, null, "Unknown action: " + action);
                return;
            }

            handler.handle(conn, id, params);

        } catch (JsonSyntaxException e) {
            BridgeMod.LOGGER.error("Invalid JSON message: {}", rawMessage);
            server.sendResponse(conn, null, false, null, "Invalid JSON: " + e.getMessage());
        } catch (Exception e) {
            BridgeMod.LOGGER.error("Error handling message: {}", e.getMessage(), e);
            server.sendResponse(conn, null, false, null, "Internal error: " + e.getMessage());
        }
    }

    // --- Built-in Handlers ---

    /**
     * Handshake: no MC thread access needed. Responds immediately.
     */
    private void handleHandshake(WebSocket conn, String id, JsonObject params) {
        String username = params.has("username") ? params.get("username").getAsString() : "unknown";
        String version = params.has("version") ? params.get("version").getAsString() : "";

        server.markHandshaked(conn, username);

        boolean baritoneAvailable = false;
        try {
            Class.forName("baritone.api.BaritoneAPI");
            baritoneAvailable = true;
        } catch (ClassNotFoundException ignored) {
        }

        JsonObject data = new JsonObject();
        data.addProperty("modVersion", BridgeMod.MOD_VERSION);
        data.addProperty("mcVersion", "1.20.1");
        data.addProperty("baritoneAvailable", baritoneAvailable);

        server.sendResponse(conn, id, true, data, null);
        BridgeMod.LOGGER.info("Handshake completed: username='{}', version='{}'", username, version);
    }

    /**
     * Chat: must execute on MC thread.
     */
    private void handleChat(WebSocket conn, String id, JsonObject params) {
        if (!params.has("message")) {
            server.sendResponse(conn, id, false, null, "Missing 'message' parameter");
            return;
        }
        String message = params.get("message").getAsString();

        Minecraft.getInstance().execute(() -> {
            ChatHandler.sendChat(message);
            server.sendResponse(conn, id, true, null, null);
        });
    }

    /**
     * Whisper: must execute on MC thread.
     */
    private void handleWhisper(WebSocket conn, String id, JsonObject params) {
        if (!params.has("username") || !params.has("message")) {
            server.sendResponse(conn, id, false, null, "Missing 'username' or 'message' parameter");
            return;
        }
        String username = params.get("username").getAsString();
        String message = params.get("message").getAsString();

        Minecraft.getInstance().execute(() -> {
            ChatHandler.sendWhisper(username, message);
            server.sendResponse(conn, id, true, null, null);
        });
    }
}
