package com.mccompanion.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public final class HandshakeRequest {
    public static final int MIN_TOKEN_LENGTH = 16;
    public static final int MAX_TOKEN_LENGTH = 512;

    private final ProtocolVersion protocol;
    private final String modVersion;
    private final String minecraftVersion;
    private final PlatformLoader loader;
    private final String worldId;
    private final CapabilitySet capabilities;
    private final String token;

    @JsonCreator
    public HandshakeRequest(
            @JsonProperty("protocol") ProtocolVersion protocol,
            @JsonProperty("modVersion") String modVersion,
            @JsonProperty("minecraftVersion") String minecraftVersion,
            @JsonProperty("loader") PlatformLoader loader,
            @JsonProperty("worldId") String worldId,
            @JsonProperty("capabilities") CapabilitySet capabilities,
            @JsonProperty("token") String token) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.modVersion = ProtocolFields.identifier(modVersion, "modVersion");
        this.minecraftVersion = validateMinecraftVersion(minecraftVersion);
        this.loader = Objects.requireNonNull(loader, "loader");
        this.worldId = ProtocolFields.identifier(worldId, "worldId");
        this.capabilities = capabilities == null ? CapabilitySet.empty() : capabilities;
        this.token = validateToken(token);
    }

    private static String validateMinecraftVersion(String version) {
        ProtocolFields.identifier(version, "minecraftVersion");
        if (!version.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:-[A-Za-z0-9.-]+)?")) {
            throw new IllegalArgumentException("minecraftVersion has an invalid format");
        }
        return version;
    }

    private static String validateToken(String token) {
        Objects.requireNonNull(token, "token");
        if (token.length() < MIN_TOKEN_LENGTH || token.length() > MAX_TOKEN_LENGTH || token.isBlank()) {
            throw new IllegalArgumentException("token must be between " + MIN_TOKEN_LENGTH + " and "
                    + MAX_TOKEN_LENGTH + " characters");
        }
        if (token.chars().anyMatch(character -> Character.isWhitespace(character)
                || Character.isISOControl(character))) {
            throw new IllegalArgumentException("token cannot contain whitespace or control characters");
        }
        return token;
    }

    @JsonProperty("protocol")
    public ProtocolVersion protocol() {
        return protocol;
    }

    @JsonProperty("modVersion")
    public String modVersion() {
        return modVersion;
    }

    @JsonProperty("minecraftVersion")
    public String minecraftVersion() {
        return minecraftVersion;
    }

    @JsonProperty("loader")
    public PlatformLoader loader() {
        return loader;
    }

    @JsonProperty("worldId")
    public String worldId() {
        return worldId;
    }

    @JsonProperty("capabilities")
    public CapabilitySet capabilities() {
        return capabilities;
    }

    @JsonProperty("token")
    public String token() {
        return token;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof HandshakeRequest that)) {
            return false;
        }
        return protocol.equals(that.protocol)
                && modVersion.equals(that.modVersion)
                && minecraftVersion.equals(that.minecraftVersion)
                && loader == that.loader
                && worldId.equals(that.worldId)
                && capabilities.equals(that.capabilities)
                && token.equals(that.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocol, modVersion, minecraftVersion, loader, worldId, capabilities, token);
    }

    @Override
    public String toString() {
        return "HandshakeRequest[protocol=" + protocol + ", modVersion=" + modVersion
                + ", minecraftVersion=" + minecraftVersion + ", loader=" + loader
                + ", worldId=" + worldId + ", capabilities=" + capabilities + ", token=<redacted>]";
    }
}
