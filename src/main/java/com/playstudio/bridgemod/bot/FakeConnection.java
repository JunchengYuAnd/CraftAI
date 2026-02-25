package com.playstudio.bridgemod.bot;

import com.playstudio.bridgemod.BridgeMod;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * A dummy network connection for fake players.
 * Sends no packets and always reports as connected.
 * Uses an EmbeddedChannel so that internal pipeline access doesn't NPE.
 */
public class FakeConnection extends Connection {

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        // We must set the private 'channel' field via reflection,
        // because placeNewPlayer() -> ServerGamePacketListenerImpl accesses channel().pipeline()
        // Find the Channel-typed field by type (works with both MojMap dev and SRG production names)
        try {
            EmbeddedChannel embeddedChannel = new EmbeddedChannel(new DummyHandler());
            boolean found = false;
            for (Field f : Connection.class.getDeclaredFields()) {
                if (Channel.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    f.set(this, embeddedChannel);
                    found = true;
                    break;
                }
            }
            if (!found) {
                BridgeMod.LOGGER.error("FakeConnection: Could not find Channel field in Connection class");
            }
        } catch (Exception e) {
            BridgeMod.LOGGER.error("Failed to set channel on FakeConnection: {}", e.getMessage());
        }
    }

    @Override
    public void send(Packet<?> packet) {
        // no-op: no real client to send to
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        // no-op
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    private static class DummyHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // no-op
        }
    }
}
