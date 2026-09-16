package com.mccompanion.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.protocol.CapabilitySet;
import com.mccompanion.protocol.ProtocolJsonCodec;

public record Handshake(
        String protocol,
        String modVersion,
        String minecraftVersion,
        String loader,
        String worldId,
        CapabilitySet capabilities
) {
    public Handshake(String protocol, String modVersion, String minecraftVersion, String loader,
                     String worldId, JsonNode capabilities) {
        this(protocol, modVersion, minecraftVersion, loader, worldId,
                decodeCapabilities(capabilities));
    }

    private static CapabilitySet decodeCapabilities(JsonNode value) {
        try { return new ProtocolJsonCodec().decode(value.toString(), CapabilitySet.class); }
        catch (com.mccompanion.protocol.ProtocolValidationException | NullPointerException invalid) {
            throw new IllegalArgumentException("Structured, versioned capabilities are required", invalid);
        }
    }

    public Handshake {
        java.util.Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.size() > 256) throw new IllegalArgumentException("too many capabilities");
    }

    public String targetId() { return loader.toLowerCase(java.util.Locale.ROOT) + "-" + minecraftVersion; }

    public Handshake withCapabilities(CapabilitySet value) {
        return new Handshake(protocol, modVersion, minecraftVersion, loader, worldId, value);
    }
}
