package com.mccompanion.runtime.capability;

import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.session.Handshake;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CapabilityVisibilityTest {
    private final CapabilityVisibility visibility = new CapabilityVisibility(CapabilityRegistry.standard());

    @Test
    void durableRecoveryAcceptanceTaskIsExposedOnlyOnConnectedFullBodies() {
        var status = Json.object().put("bodyState", "spawned").put("runtimeConnected", true);
        for (var target : List.of(new String[]{"fabric", "1.21.1"}, new String[]{"forge", "1.20.1"})) {
            var snapshot = visibility.resolve(handshake(target[0], target[1],
                    Json.object().<com.fasterxml.jackson.databind.node.ObjectNode>set("BuildSmallBlueprint", com.mccompanion.runtime.ProtocolTestCapabilities.available())), status);
            assertEquals(List.of("BuildSmallBlueprint"), snapshot.availableNames());
        }
    }

    @Test
    void exposesOnlyFormallyImplementedConnectedFabricCapabilities() {
        var capabilities = Json.object().<com.fasterxml.jackson.databind.node.ObjectNode>set("NavigateTo", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("FollowOwner", com.mccompanion.runtime.ProtocolTestCapabilities.available())
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("DeliverItem", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("EatAndRecover", com.mccompanion.runtime.ProtocolTestCapabilities.available())
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("CraftItem", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("LookAt", com.mccompanion.runtime.ProtocolTestCapabilities.available())
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("InteractBlock", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("InteractEntity", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("MenuAction", com.mccompanion.runtime.ProtocolTestCapabilities.available())
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("UseItem", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("DropItem", com.mccompanion.runtime.ProtocolTestCapabilities.available());
        var status = Json.object().put("bodyState", "spawned").put("runtimeConnected", true);

        var snapshot = visibility.resolve(handshake("fabric", "1.21.1", capabilities), status);

        assertEquals(List.of("CraftItem", "DeliverItem", "DropItem", "EatAndRecover", "InteractBlock",
                        "InteractEntity", "LookAt", "MenuAction", "UseItem",
                        "FollowOwner", "NavigateTo").stream().sorted().toList(),
                snapshot.availableNames());
        assertEquals("AVAILABLE_NOW", snapshot.toJson().path("CraftItem").path("state").asText());
    }

    @Test
    void exposesFormallyImplementedConnectedForgeCapabilities() {
        var capabilities = Json.object().<com.fasterxml.jackson.databind.node.ObjectNode>set("NavigateTo", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("FollowOwner", com.mccompanion.runtime.ProtocolTestCapabilities.available());
        var status = Json.object().put("bodyState", "spawned").put("runtimeConnected", true);

        var snapshot = visibility.resolve(handshake("forge", "1.20.1", capabilities), status);

        assertEquals(List.of("FollowOwner", "NavigateTo"), snapshot.availableNames());
        assertEquals("AVAILABLE_NOW", snapshot.toJson().path("FollowOwner").path("state").asText());
    }

    @Test
    void exposesNavigateWithWorldChangesWhenTheConnectedBodyDeclaresIt() {
        var capabilities = Json.object().<com.fasterxml.jackson.databind.node.ObjectNode>set("NavigateWithWorldChanges", com.mccompanion.runtime.ProtocolTestCapabilities.available());
        var status = Json.object().put("bodyState", "spawned").put("runtimeConnected", true);

        var snapshot = visibility.resolve(handshake("fabric", "1.21.1", capabilities), status);

        assertEquals(List.of("NavigateWithWorldChanges"), snapshot.availableNames());
        assertEquals("AVAILABLE_NOW",
                snapshot.toJson().path("NavigateWithWorldChanges").path("state").asText());
    }

    @Test
    void exposesDailyActionCapabilitiesWhenTheConnectedBodyDeclaresThem() {
        var capabilities = Json.object().<com.fasterxml.jackson.databind.node.ObjectNode>set("EquipItem", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("SleepAtBed", com.mccompanion.runtime.ProtocolTestCapabilities.available())
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("UseWaterBucket", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("UseVehicle", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("Fish", com.mccompanion.runtime.ProtocolTestCapabilities.available())
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("FarmCrop", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("BreedAnimals", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("TradeWithVillager", com.mccompanion.runtime.ProtocolTestCapabilities.available())
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("EnchantItem", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("BrewPotion", com.mccompanion.runtime.ProtocolTestCapabilities.available()).<com.fasterxml.jackson.databind.node.ObjectNode>set("GlideWithElytra", com.mccompanion.runtime.ProtocolTestCapabilities.available());
        var status = Json.object().put("bodyState", "spawned").put("runtimeConnected", true);

        var snapshot = visibility.resolve(handshake("fabric", "1.21.1", capabilities), status);

        assertEquals(List.of("BreedAnimals", "BrewPotion", "EnchantItem", "EquipItem", "FarmCrop",
                        "Fish", "GlideWithElytra", "SleepAtBed", "TradeWithVillager", "UseVehicle",
                        "UseWaterBucket").stream().sorted().toList(),
                snapshot.availableNames().stream().filter(name -> capabilities.has(name)).sorted().toList());
    }

    @Test
    void distinguishesImplementedConnectedBlockedAndUnsupportedStates() {
        var disconnected = visibility.resolve(null, Json.object());
        assertEquals("IMPLEMENTED", disconnected.toJson().path("NavigateTo").path("state").asText());

        var missingStatus = visibility.resolve(handshake("fabric", "1.21.1",
                Json.object().<com.fasterxml.jackson.databind.node.ObjectNode>set("NavigateTo", com.mccompanion.runtime.ProtocolTestCapabilities.available())), Json.object());
        assertEquals("CONNECTED", missingStatus.toJson().path("NavigateTo").path("state").asText());

        var sleeping = visibility.resolve(handshake("fabric", "1.21.1",
                        Json.object().<com.fasterxml.jackson.databind.node.ObjectNode>set("NavigateTo", com.mccompanion.runtime.ProtocolTestCapabilities.available())),
                Json.object().put("bodyState", "sleeping").put("runtimeConnected", true));
        assertEquals("TEMPORARILY_BLOCKED", sleeping.toJson().path("NavigateTo").path("state").asText());

        var undeclared = visibility.resolve(handshake("fabric", "1.21.1", Json.object()),
                Json.object().put("bodyState", "spawned").put("runtimeConnected", true));
        assertEquals("UNSUPPORTED", undeclared.toJson().path("NavigateTo").path("state").asText());
    }

    @Test
    void rejectsImplementedCapabilityOnUnsupportedLoader() {
        var snapshot = visibility.resolve(handshake("neoforge", "1.21.1",
                        Json.object().<com.fasterxml.jackson.databind.node.ObjectNode>set("NavigateTo", com.mccompanion.runtime.ProtocolTestCapabilities.available())),
                Json.object().put("bodyState", "spawned").put("runtimeConnected", true));
        assertEquals(List.of(), snapshot.availableNames());
        assertEquals("LOADER_OR_VERSION_UNSUPPORTED",
                snapshot.toJson().path("NavigateTo").path("reason").asText());
    }

    private static Handshake handshake(String loader, String minecraft, com.fasterxml.jackson.databind.JsonNode capabilities) {
        return new Handshake("mc-companion/2", "0.3.0", minecraft, loader, "world", capabilities);
    }
}
