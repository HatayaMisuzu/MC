package com.mccompanion.runtime;

public final class ProtocolTestCapabilities {
    private ProtocolTestCapabilities() { }
    public static com.fasterxml.jackson.databind.node.ObjectNode available() {
        var result = com.mccompanion.runtime.json.Json.object().put("availability", "available").put("version", "1.0");
        result.putObject("attributes");
        return result;
    }
}
