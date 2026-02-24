package com.playstudio.bridgemod;

import com.playstudio.bridgemod.handler.QueryHandler;
import com.playstudio.bridgemod.state.EventForwarder;
import com.playstudio.bridgemod.state.StateSyncManager;
import com.playstudio.bridgemod.websocket.BridgeWebSocketServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bridge Mod entry point.
 * A client-side Forge mod that runs a WebSocket server to bridge
 * Mindcraft (Node.js AI framework) with the Minecraft client.
 */
@Mod(BridgeMod.MOD_ID)
public class BridgeMod {

    public static final String MOD_ID = "bridgemod";
    public static final String MOD_VERSION = "1.0.0";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private static final int WEBSOCKET_PORT = 8089;
    private static BridgeWebSocketServer wsServer;

    public BridgeMod() {
        LOGGER.info("Bridge Mod {} initializing", MOD_VERSION);
        FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Bridge Mod client setup - starting WebSocket server on port {}", WEBSOCKET_PORT);

        // Create and start WebSocket server
        wsServer = new BridgeWebSocketServer(WEBSOCKET_PORT);
        try {
            wsServer.start();
        } catch (Exception e) {
            LOGGER.error("Failed to start WebSocket server on port {}: {}", WEBSOCKET_PORT, e.getMessage());
            return;
        }

        // Register query handlers (Phase 2)
        QueryHandler queryHandler = new QueryHandler(wsServer);
        queryHandler.registerAll(wsServer.getMessageHandler());

        // Register event listeners on Forge event bus
        MinecraftForge.EVENT_BUS.register(new StateSyncManager(wsServer));
        MinecraftForge.EVENT_BUS.register(new EventForwarder(wsServer));

        // Shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (wsServer != null) {
                LOGGER.info("Shutting down WebSocket server...");
                try {
                    wsServer.stop(1000);
                } catch (InterruptedException e) {
                    LOGGER.warn("WebSocket server shutdown interrupted");
                }
            }
        }));

        LOGGER.info("Bridge Mod Phase 2 ready - WebSocket, StateSync, EventForwarder, QueryHandler active");
    }

    /**
     * Get the WebSocket server instance (for external access if needed).
     */
    public static BridgeWebSocketServer getServer() {
        return wsServer;
    }
}
