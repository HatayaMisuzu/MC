package com.mccompanion.runtime.worldmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.event.RuntimeEvent;
import com.mccompanion.runtime.memory.MemoryFact;
import com.mccompanion.runtime.memory.MemoryKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldModelTest {
    static final Instant NOW = Instant.parse("2026-09-02T01:00:00Z");
    static final String DIM = "minecraft:overworld";
    static final String PLAYER = "00000000-0000-0000-0000-000000000001";

    static ObjectNode status(Instant time) {
        ObjectNode value = Json.object().put("observedAt", time.toString()).put("dimension", DIM)
                .put("runtimeConnected", true).put("bodyState", "spawned");
        value.set("position", position(1));
        value.set("vitals", Json.object().put("health", 20).put("maxHealth", 20));
        value.set("equipment", Json.object().put("mainHand", "minecraft:stick"));
        value.set("inventory", Json.object().put("freeSlots", 35).set("counts", Json.object().put("minecraft:stick", 1)));
        value.set("menu", Json.object().put("open", false));
        value.set("vehicle", Json.object().put("seated", false));
        value.set("navigation", Json.object().put("active", true).put("waypointIndex", 1));
        value.putArray("observedContainers").add(position(2).put("type", "minecraft:chest").put("verified", true));
        value.putObject("localWorld").putArray("entities").add(position(3).put("entityId", PLAYER).put("player", true));
        return value;
    }

    static ObjectNode position(int x) { return Json.object().put("dimension", DIM).put("x", x).put("y", 64).put("z", 0); }

    @Test void snapshotsExpireAndReadsNeverRefreshTheirTtl() {
        WorldModel model = new WorldModel(null);
        model.observe("world", "session", status(NOW), NOW);
        assertTrue(model.current("inventory", NOW).path("verified").asBoolean());
        JsonNode expired = model.current("inventory", NOW.plusMillis(15_000));
        assertTrue(expired.path("stale").asBoolean());
        assertFalse(expired.path("verified").asBoolean());
        assertEquals("TTL_EXPIRED", expired.path("invalidation").asText());
        assertTrue(model.summary(NOW.plusSeconds(20), List.of()).path("nearby").get(0).path("stale").asBoolean());
        assertEquals(model.saved(), new WorldModel(model.saved()).saved());
    }

    @Test void stableIdentitiesRefreshWithoutDuplicatesAndDisappearWithoutInventingDeath() {
        WorldModel model = new WorldModel(null);
        model.observe("world", "session", status(NOW), NOW);
        ObjectNode moved = status(NOW.plusSeconds(1));
        ((ObjectNode) moved.path("localWorld").path("entities").get(0)).put("x", 8);
        model.observe("world", "session", moved, NOW.plusSeconds(1));
        String key = "entity:" + PLAYER;
        assertEquals(8, model.saved().path("nearby").path(key).path("value").path("x").asInt());
        assertEquals("", model.saved().path("nearby").path(key).path("invalidation").asText());
        ObjectNode absent = status(NOW.plusSeconds(2));
        ((ObjectNode) absent.path("localWorld")).putArray("entities");
        model.observe("world", "session", absent, NOW.plusSeconds(2));
        assertEquals("NOT_OBSERVED", model.saved().path("memory").path(key).path("invalidation").asText());
        assertEquals(2, model.saved().path("nearby").size());
        // An old snapshot cannot resurrect the location, and a fresh one can.
        model.observe("world", "session", moved, NOW.plusSeconds(3));
        assertEquals("NOT_OBSERVED", model.saved().path("nearby").path(key).path("invalidation").asText());
        model.observe("world", "session", status(NOW.plusSeconds(4)), NOW.plusSeconds(4));
        assertEquals("", model.saved().path("nearby").path(key).path("invalidation").asText());
    }

    @Test void eventsInvalidateExactIdentityAndOlderEventsDoNotUndoNewEvidence() {
        WorldModel model = new WorldModel(null);
        model.observe("world", "session", status(NOW), NOW);
        model.event(event("CURRENT_TARGET_DIED", PLAYER, NOW.plusMillis(100)));
        assertEquals("CURRENT_TARGET_DIED", model.saved().path("memory").path("entity:" + PLAYER).path("invalidation").asText());
        assertEquals("", model.saved().path("memory").path("container:" + DIM + ":2,64,0").path("invalidation").asText());
        model.event(event("TARGET_CONTAINER_CHANGED", DIM + ":2,64,0", NOW.plusMillis(200)));
        assertEquals("TARGET_CONTAINER_CHANGED", model.saved().path("memory").path("container:" + DIM + ":2,64,0").path("invalidation").asText());
        model.observe("world", "session", status(NOW.plusSeconds(1)), NOW.plusSeconds(1));
        model.event(event("CURRENT_TARGET_DIED", PLAYER, NOW));
        assertEquals("", model.saved().path("nearby").path("entity:" + PLAYER).path("invalidation").asText());
    }

    @Test void dimensionSessionDeathAndWorldChangesDoNotReuseCurrentFacts() {
        WorldModel model = new WorldModel(null);
        model.observe("world", "session", status(NOW), NOW);
        model.event(event("DIMENSION_CHANGED", "", NOW.plusMillis(1)));
        assertFalse(model.current("position", NOW.plusMillis(1)).path("verified").asBoolean());
        ObjectNode other = status(NOW.plusSeconds(1)).put("dimension", "example:moon");
        model.observe("world", "session", other, NOW.plusSeconds(1));
        assertTrue(model.summary(NOW.plusSeconds(1), List.of()).path("memory").findValuesAsText("invalidation").contains("DIMENSION_CHANGED"));
        model.invalidateAll("DISCONNECTED", NOW.plusSeconds(2));
        assertFalse(model.current("position", NOW.plusSeconds(2)).path("verified").asBoolean());
        model.observe("new-world", "new-session", status(NOW.plusSeconds(3)), NOW.plusSeconds(3));
        assertEquals("new-world", model.saved().path("worldId").asText());
        assertEquals(3, model.saved().path("memory").size());
        model.event(event("DEATH", "", NOW.plusSeconds(4)));
        assertFalse(model.current("vitals", NOW.plusSeconds(4)).path("verified").asBoolean());
    }

    @Test void dormantMissingFutureAndTruncatedSnapshotsNeverVerifySyntheticData() {
        for (ObjectNode invalid : List.of(status(NOW).put("bodyState", "sleeping"),
                status(NOW).put("observedAt", "bad"), status(NOW.plusSeconds(30)),
                status(NOW).put("runtimeConnected", false))) {
            WorldModel model = new WorldModel(null);
            model.observe("world", "session", invalid, NOW);
            assertFalse(model.current("position", NOW).path("verified").asBoolean());
        }
        WorldModel model = new WorldModel(null);
        ObjectNode huge = status(NOW);
        ObjectNode inventory = huge.putObject("inventory");
        for (int i = 0; i < 1000; i++) inventory.put("item" + i, "x".repeat(500));
        model.observe("world", "session", huge, NOW);
        assertFalse(model.current("inventory", NOW).path("verified").asBoolean());
    }

    @Test void capacityAndSerializedSummaryAreHardBoundsEvenWithEscapedText() {
        WorldModel model = new WorldModel(null);
        for (int t = 0; t < 100; t++) {
            Instant at = NOW.plusMillis(t);
            ObjectNode snapshot = status(at);
            var entities = ((ObjectNode) snapshot.path("localWorld")).putArray("entities");
            for (int i = 0; i < 32; i++) entities.add(position(i).put("entityId", UUID.randomUUID().toString())
                    .put("player", true).put("name", "\"\\\n".repeat(2000)));
            model.observe("world", "session", snapshot, at);
        }
        assertTrue(model.saved().path("current").size() <= WorldModel.MAX_CURRENT);
        assertTrue(model.saved().path("nearby").size() <= WorldModel.MAX_NEARBY);
        assertTrue(model.saved().path("memory").size() <= WorldModel.MAX_MEMORY);
        ObjectNode summary = model.summary(NOW.plusMillis(100), List.of());
        assertTrue(Json.write(summary).length() <= WorldModel.SUMMARY_CHARS);
        assertTrue(summary.path("emittedEntries").asInt() <= WorldModel.SUMMARY_ENTRIES);
        assertTrue(summary.path("omittedEntries").asInt() > 0);
        assertFalse(Json.write(summary).contains(WorldModel.STORAGE_FIELD));
    }

    @Test void homeRequiresExistingUserApprovalAndDoesNotBecomeVerifiedWorldState() {
        WorldModel model = new WorldModel(null);
        model.observe("world", "session", status(NOW), NOW);
        MemoryFact inferred = landmark("INFERENCE", true), approved = landmark("USER", true);
        assertFalse(WorldModel.explicitLandmark(inferred));
        JsonNode summary = model.summary(NOW, List.of(inferred, approved));
        JsonNode home = summary.path("memory").get(0);
        assertEquals("home", home.path("identity").asText());
        assertTrue(home.path("userDeclared").asBoolean());
        assertFalse(home.path("verified").asBoolean());
        assertEquals(-1, home.path("ttlMillis").asLong());
        assertFalse(Json.write(model.saved()).contains("home"));
        assertFalse(Json.write(model.summary(NOW, List.of())).contains("home"));
    }

    @Test void liveQueryUpdatesSameModelAndRuntimeLocationsRemainIntent() {
        WorldModel model = new WorldModel(null);
        model.observe("world", "session", status(NOW), NOW);
        ObjectNode block = Json.object().put("verified", true).put("dimension", DIM)
                .put("observedAt", NOW.plusMillis(1).toString()).put("block", "mod:ore");
        block.set("position", position(7));
        model.observeQuery("block.inspect", block, NOW.plusMillis(1));
        assertEquals("mod:ore", model.saved().path("nearby").path("target:" + DIM + ":7,64,0").path("value").path("block").asText());
        model.runtime("task", Json.object().put("state", "RUNNING"), position(9), NOW.plusMillis(2));
        assertFalse(model.saved().path("memory").path("task:" + DIM + ":9,64,0").path("verified").asBoolean());
    }

    @Test void droppingATargetBindingDoesNotInvalidateTheStillVisibleEntity() {
        WorldModel model = new WorldModel(null);
        ObjectNode before = status(NOW);
        ((ObjectNode) before.path("localWorld")).putArray("targets").add(position(3).put("entityId", PLAYER));
        model.observe("world", "session", before, NOW);
        model.observe("world", "session", status(NOW.plusSeconds(1)), NOW.plusSeconds(1));
        var nearby = model.summary(NOW.plusSeconds(1), List.of()).path("nearby");
        boolean freshPlayer = false, staleTarget = false;
        for (JsonNode entry : nearby) {
            if (entry.path("kind").asText().equals("player")) freshPlayer = entry.path("verified").asBoolean();
            if (entry.path("kind").asText().equals("target")) staleTarget = entry.path("stale").asBoolean();
        }
        assertTrue(freshPlayer);
        assertTrue(staleTarget);
    }

    @Test void clippedStringsAndShortUuidAliasesCannotMasqueradeAsCompleteFacts() {
        WorldModel model = new WorldModel(null);
        ObjectNode status = status(NOW);
        status.putObject("equipment").put("mainHand", "x".repeat(300));
        ((ObjectNode) status.path("localWorld")).putArray("entities").add(position(1).put("entityId", "1-1-1-1-1"));
        model.observe("world", "session", status, NOW);
        assertFalse(model.current("equipment", NOW).path("verified").asBoolean());
        assertEquals(1, model.saved().path("nearby").size());
        assertFalse(Json.write(model.saved()).contains("1-1-1-1-1"));
    }

    @Test void explicitLandmarksAreWorldScopedAndExpiredDeclarationsAreOmitted() {
        WorldModel model = new WorldModel(null);
        model.observe("other-world", "session", status(NOW), NOW);
        assertFalse(Json.write(model.summary(NOW, List.of(landmark("USER", true)))).contains("home"));
        model.observe("world", "session", status(NOW), NOW);
        MemoryFact expired = new MemoryFact("memory", "c1", MemoryKind.WORLD, "home",
                position(10).put("worldId", "world"), true, 1, "USER", NOW, NOW.minusSeconds(60), NOW.minusSeconds(60));
        assertFalse(Json.write(model.summary(NOW, List.of(expired))).contains("home"));
    }

    private static MemoryFact landmark(String source, boolean verified) {
        return new MemoryFact("memory", "c1", MemoryKind.WORLD, "home", position(10).put("worldId", "world"), verified,
                1.0, source, null, NOW, NOW);
    }

    static RuntimeEvent event(String type, String identity, Instant at) {
        ObjectNode target = Json.object().put("identity", identity).put("entityId", identity);
        return new RuntimeEvent(UUID.randomUUID().toString(), RuntimeEvent.Category.WORLD, type,
                RuntimeEvent.Priority.HIGH, "MINECRAFT_OBSERVER", "c1", null, null, target,
                UUID.randomUUID().toString(), null, null, at, at, at.plusSeconds(60),
                Json.object().set("target", target));
    }
}
