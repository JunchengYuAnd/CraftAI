package com.playstudio.bridgemod.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Handles chat and whisper commands.
 * All methods MUST be called on the MC client thread.
 */
public class ChatHandler {

    /**
     * Send a chat message or command as the player.
     * If the message starts with "/", it is sent as a command.
     */
    public static void sendChat(String message) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) return;

        if (message.startsWith("/")) {
            // Send as command (strip the leading "/")
            player.connection.sendCommand(message.substring(1));
        } else {
            // Send as regular chat message
            player.connection.sendChat(message);
        }
    }

    /**
     * Send a whisper (private message) to a specific player.
     */
    public static void sendWhisper(String username, String message) {
        sendChat("/msg " + username + " " + message);
    }
}
