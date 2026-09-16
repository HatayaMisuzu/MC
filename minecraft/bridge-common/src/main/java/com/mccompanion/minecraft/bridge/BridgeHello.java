package com.mccompanion.minecraft.bridge;

import com.mccompanion.protocol.BuildIdentity;
import com.mccompanion.protocol.CapabilitySet;
import java.util.LinkedHashMap;
import java.util.Map;

/** One handshake projection for every FULL Runtime Bridge target. */
public final class BridgeHello {
    private BridgeHello() { }

    public static Map<String, Object> create(String targetId, String minecraftVersion, String loader,
                                             String worldId, String installationId, String instanceId,
                                             String launcherType, CapabilitySet capabilities) {
        var installation = new LinkedHashMap<String, String>();
        installation.put("installationId", installationId);
        installation.put("instanceId", instanceId);
        installation.put("launcherType", launcherType);
        return create(targetId, minecraftVersion, loader, worldId, installation, capabilities);
    }

    public static Map<String, Object> create(String targetId, String minecraftVersion, String loader,
                                             String worldId, Map<String, String> installation,
                                             CapabilitySet capabilities) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("protocol", BuildIdentity.PROTOCOL);
        payload.put("modVersion", BuildIdentity.PRODUCT_VERSION);
        payload.put("minecraftVersion", minecraftVersion);
        payload.put("loader", loader);
        payload.put("worldId", worldId);
        payload.put("targetId", targetId);
        payload.put("capabilityRevision", 0);
        if (installation != null) installation.forEach((key, value) -> {
            if (value != null && !value.isBlank()) payload.put(key, value);
        });
        var advertised = new LinkedHashMap<String, Object>();
        capabilities.asMap().forEach((name, descriptor) -> advertised.put(name, Map.of(
                "availability", descriptor.availability().toWire(),
                "version", descriptor.version(),
                "attributes", descriptor.attributes())));
        payload.put("capabilities", advertised);
        return BridgeValues.freeze(payload);
    }
}
