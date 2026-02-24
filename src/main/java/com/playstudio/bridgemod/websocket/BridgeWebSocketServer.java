package com.playstudio.bridgemod.websocket;

import com.google.gson.JsonObject;
import com.playstudio.bridgemod.BridgeMod;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket server that bridges Mindcraft (Node.js) and the Minecraft client.
 * Listens on a configurable port (default 8089) for JSON text frame messages.
 */
public class BridgeWebSocketServer extends WebSocketServer {

    private final ConcurrentHashMap<WebSocket, ConnectionState> connections = new ConcurrentHashMap<>();
    private final MessageHandler messageHandler;

    /**
     * Per-connection state tracker.
     */
    public static class ConnectionState {
        public volatile boolean handshaked = false;
        public volatile String username = null;
        public final long connectedAt = System.currentTimeMillis();
    }

    public BridgeWebSocketServer(int port) {
        super(new InetSocketAddress(port));
        this.messageHandler = new MessageHandler(this);
        this.setReuseAddr(true);
    }

    /**
     * Start the WebSocket server on a daemon thread so it doesn't block JVM shutdown.
     */
    @Override
    public void start() {
        Thread serverThread = new Thread(this, "BridgeMod-WebSocket");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        connections.put(conn, new ConnectionState());
        BridgeMod.LOGGER.info("WebSocket client connected: {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connections.remove(conn);
        BridgeMod.LOGGER.info("WebSocket client disconnected: code={}, reason={}, remote={}",
                code, reason, remote);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        messageHandler.handleMessage(conn, message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        BridgeMod.LOGGER.error("WebSocket error on connection {}: {}",
                conn != null ? conn.getRemoteSocketAddress() : "null", ex.getMessage());
        if (conn != null) {
            connections.remove(conn);
        }
    }

    @Override
    public void onStart() {
        BridgeMod.LOGGER.info("Bridge Mod WebSocket server started on port {}", getPort());
    }

    // --- Public API ---

    /**
     * Mark a connection as having completed the handshake.
     */
    public void markHandshaked(WebSocket conn, String username) {
        ConnectionState state = connections.get(conn);
        if (state != null) {
            state.handshaked = true;
            state.username = username;
        }
    }

    /**
     * Check if there are any handshaked connections.
     */
    public boolean hasHandshakedConnections() {
        for (ConnectionState state : connections.values()) {
            if (state.handshaked) return true;
        }
        return false;
    }

    /**
     * Broadcast a message to all handshaked connections.
     * Thread-safe: can be called from any thread (WebSocket.send() uses internal queue).
     */
    public void broadcastToHandshaked(String message) {
        for (var entry : connections.entrySet()) {
            if (entry.getValue().handshaked && entry.getKey().isOpen()) {
                try {
                    entry.getKey().send(message);
                } catch (Exception e) {
                    BridgeMod.LOGGER.warn("Failed to send to {}: {}",
                            entry.getKey().getRemoteSocketAddress(), e.getMessage());
                }
            }
        }
    }

    /**
     * Send a protocol response to a specific connection.
     */
    public void sendResponse(WebSocket conn, String id, boolean success, JsonObject data, String error) {
        if (conn != null && conn.isOpen()) {
            try {
                conn.send(Protocol.response(id, success, data, error));
            } catch (Exception e) {
                BridgeMod.LOGGER.warn("Failed to send response: {}", e.getMessage());
            }
        }
    }

    /**
     * Get the MessageHandler (for registering additional action handlers).
     */
    public MessageHandler getMessageHandler() {
        return messageHandler;
    }
}
