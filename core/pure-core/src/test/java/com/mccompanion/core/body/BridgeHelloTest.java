package com.mccompanion.core.body;

import com.mccompanion.minecraft.bridge.BridgeHello;
import com.mccompanion.protocol.BuildIdentity;
import com.mccompanion.protocol.CapabilityAvailability;
import com.mccompanion.protocol.CapabilityDescriptor;
import com.mccompanion.protocol.CapabilitySet;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class BridgeHelloTest {
    @Test void projectsTargetAndStructuredActualCapabilities() {
        var hello = BridgeHello.create("fabric-1.21.1", "1.21.1", "fabric", "world-1",
                Map.of("installationId", "install-1"), new CapabilitySet(Map.of(
                        "MenuAction", new CapabilityDescriptor(CapabilityAvailability.AVAILABLE,
                                "1.0", Map.of("mode", "observed")))));

        assertEquals(BuildIdentity.PROTOCOL, hello.get("protocol"));
        assertEquals("fabric-1.21.1", hello.get("targetId"));
        assertEquals("install-1", hello.get("installationId"));
        var capability = (Map<?, ?>) ((Map<?, ?>) hello.get("capabilities")).get("MenuAction");
        assertEquals("available", capability.get("availability"));
        assertEquals("1.0", capability.get("version"));
        assertFalse(((Map<?, ?>) capability.get("attributes")).isEmpty());
    }
}
