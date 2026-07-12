package com.mccompanion.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;

public record Handshake(
        String protocol,
        String modVersion,
        String minecraftVersion,
        String loader,
        String worldId,
        JsonNode capabilities
) {
    public Handshake {
        capabilities = capabilities == null ? com.mccompanion.runtime.json.Json.object() : capabilities.deepCopy();
    }
}
