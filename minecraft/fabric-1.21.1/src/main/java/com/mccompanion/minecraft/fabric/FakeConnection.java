package com.mccompanion.minecraft.fabric;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A deliberately packetless server-bound connection for a server-owned player body.
 *
 * <p>Vanilla's {@link Connection} queues outbound packets while no Netty channel exists. A fake player can
 * therefore leak an unbounded packet queue if it merely constructs a normal connection and never connects it.
 * This implementation consumes every outbound packet immediately and only keeps a monotonic diagnostic count.
 * It does not retain packets or packet payloads.</p>
 */
public final class FakeConnection extends Connection {
    private static final SocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    private final AtomicLong discardedPackets = new AtomicLong();
    private volatile PacketListener packetListener;
    private volatile DisconnectionDetails disconnectionDetails;
    private volatile boolean connected = true;

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
        packetListener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
        // There is no client-side protocol or channel for a server-owned body.
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
    public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
        send(packet, listener);
    }

    @Override
    public void runOnceConnected(Consumer<Connection> action) {
        Objects.requireNonNull(action, "action").accept(this);
    }

    @Override
    public void flushChannel() {
        // Packets are consumed synchronously by send(...).
    }

    @Override
    public void tick() {
        // There is no network state to tick.
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return ADDRESS;
    }

    @Override
    public String getLoggableAddress(boolean logIp) {
        return logIp ? "companion@127.0.0.1" : "companion@local";
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
    public DisconnectionDetails getDisconnectionDetails() {
        return disconnectionDetails;
    }

    @Override
    public void disconnect(Component reason) {
        disconnect(new DisconnectionDetails(reason));
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        disconnectionDetails = Objects.requireNonNull(details, "details");
        connected = false;
    }

    @Override
    public void handleDisconnection() {
        connected = false;
    }

    public long discardedPacketCount() {
        return discardedPackets.get();
    }

    /** Outbound packets are consumed synchronously, so this value is invariantly zero. */
    public int retainedPacketCount() {
        return 0;
    }
}
