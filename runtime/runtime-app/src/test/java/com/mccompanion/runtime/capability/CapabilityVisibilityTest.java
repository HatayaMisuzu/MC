package com.mccompanion.runtime.capability;

import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.session.Handshake;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CapabilityVisibilityTest {
    private final CapabilityVisibility visibility = new CapabilityVisibility(CapabilityRegistry.standard());

    @Test
    void exposesOnlyFormallyImplementedConnectedFabricCapabilities() {
        var capabilities = Json.object().put("NavigateTo", true).put("FollowOwner", true)
                .put("DeliverItem", true).put("EatAndRecover", true)
                .put("CraftItem", true).put("LookAt", true)
                .put("InteractBlock", true).put("InteractEntity", true).put("MenuAction", true);
        var status = Json.object().put("bodyState", "spawned").put("runtimeConnected", true);

        var snapshot = visibility.resolve(handshake("fabric", "1.21.1", capabilities), status);

        assertEquals(List.of("CraftItem", "DeliverItem", "EatAndRecover", "InteractBlock",
                        "InteractEntity", "LookAt", "MenuAction", "FollowOwner", "NavigateTo").stream().sorted().toList(),
                snapshot.availableNames());
        assertEquals("AVAILABLE_NOW", snapshot.toJson().path("CraftItem").path("state").asText());
    }

    @Test
    void distinguishesImplementedConnectedBlockedAndUnsupportedStates() {
        var disconnected = visibility.resolve(null, Json.object());
        assertEquals("IMPLEMENTED", disconnected.toJson().path("NavigateTo").path("state").asText());

        var missingStatus = visibility.resolve(handshake("fabric", "1.21.1",
                Json.object().put("NavigateTo", true)), Json.object());
        assertEquals("CONNECTED", missingStatus.toJson().path("NavigateTo").path("state").asText());

        var sleeping = visibility.resolve(handshake("fabric", "1.21.1",
                        Json.object().put("NavigateTo", true)),
                Json.object().put("bodyState", "sleeping").put("runtimeConnected", true));
        assertEquals("TEMPORARILY_BLOCKED", sleeping.toJson().path("NavigateTo").path("state").asText());

        var undeclared = visibility.resolve(handshake("fabric", "1.21.1", Json.object()),
                Json.object().put("bodyState", "spawned").put("runtimeConnected", true));
        assertEquals("UNSUPPORTED", undeclared.toJson().path("NavigateTo").path("state").asText());
    }

    @Test
    void rejectsImplementedCapabilityOnUnsupportedLoader() {
        var snapshot = visibility.resolve(handshake("neoforge", "1.21.1",
                        Json.object().put("NavigateTo", true)),
                Json.object().put("bodyState", "spawned").put("runtimeConnected", true));
        assertEquals(List.of(), snapshot.availableNames());
        assertEquals("LOADER_OR_VERSION_UNSUPPORTED",
                snapshot.toJson().path("NavigateTo").path("reason").asText());
    }

    private static Handshake handshake(String loader, String minecraft, com.fasterxml.jackson.databind.JsonNode capabilities) {
        return new Handshake("mc-companion/1", "0.3.0", minecraft, loader, "world", capabilities);
    }
}
