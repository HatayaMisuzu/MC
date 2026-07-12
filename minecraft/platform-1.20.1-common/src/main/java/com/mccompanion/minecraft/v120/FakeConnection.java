package com.mccompanion.minecraft.v120;

import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** Packetless connection with a socket-free channel attribute map for Forge login hooks. */
public final class FakeConnection extends Connection {
    private static final SocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    private final AtomicLong discardedPackets = new AtomicLong();
    private final EmbeddedChannel attributeChannel;
    private volatile PacketListener packetListener;
    private volatile Component disconnectedReason;
    private volatile boolean connected = true;

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        attributeChannel = new EmbeddedChannel(this);
    }

    @Override
    public void setListener(PacketListener listener) {
        packetListener = Objects.requireNonNull(listener, "listener");
        super.setListener(listener);
    }

    @Override
    public void send(Packet<?> packet) {
        Objects.requireNonNull(packet, "packet");
        discardedPackets.incrementAndGet();
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener) {
        send(packet);
        if (listener != null) {
            listener.onSuccess();
        }
    }

    @Override
    public void tick() {
        // No network state or queued packets exist.
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return ADDRESS;
    }

    @Override
    public boolean isMemoryConnection() {
        return true;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    @Override
    public PacketListener getPacketListener() {
        return packetListener;
    }

    @Override
    public Component getDisconnectedReason() {
        return disconnectedReason;
    }

    @Override
    public void disconnect(Component reason) {
        disconnectedReason = Objects.requireNonNull(reason, "reason");
        connected = false;
        attributeChannel.finishAndReleaseAll();
    }

    @Override
    public void handleDisconnection() {
        connected = false;
        attributeChannel.finishAndReleaseAll();
    }

    public long discardedPacketCount() {
        return discardedPackets.get();
    }

    public int retainedPacketCount() {
        return attributeChannel.inboundMessages().size() + attributeChannel.outboundMessages().size();
    }
}
