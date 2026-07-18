package com.mccompanion.runtime.memory;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRepositoryTest {
    @TempDir Path temporary;

    @Test
    void inferenceCannotOverwriteVerifiedWorldFact() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("memory.db"))) {
            database.initialize();
            MemoryRepository repository = new MemoryRepository(database);
            repository.remember("c1", MemoryKind.WORLD, "landmark:base", Json.object().put("x", 10), true, 1, null);
            MemoryFact retained = repository.remember("c1", MemoryKind.WORLD, "landmark:base",
                    Json.object().put("x", 999), false, .4, Duration.ofMinutes(5));
            assertTrue(retained.verified());
            assertEquals(10, retained.value().path("x").asInt());
        }
    }

    @Test
    void staleWorldFactExpiresAndIsNotRetrieved() throws Exception {
        Instant now = Instant.parse("2026-07-15T00:00:00Z");
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("expiry.db"))) {
            database.initialize();
            MemoryRepository writer = new MemoryRepository(database, Clock.fixed(now, ZoneOffset.UTC));
            writer.remember("c1", MemoryKind.WORLD, "container:base", Json.object().put("present", true), true, 1,
                    Duration.ofSeconds(10));
            assertEquals(1, writer.relevant("c1", MemoryKind.WORLD, 10).size());
            MemoryRepository later = new MemoryRepository(database, Clock.fixed(now.plusSeconds(11), ZoneOffset.UTC));
            assertTrue(later.relevant("c1", MemoryKind.WORLD, 10).isEmpty());
            assertEquals(1, later.expire());
        }
    }

    @Test
    void bodyVerifiedContainersBecomeDurablePlanningContextWithoutInventingContents() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("containers.db"))) {
            database.initialize();
            MemoryRepository repository = new MemoryRepository(database);
            var status = Json.object().put("bodyState", "spawned");
            status.putArray("observedContainers").addObject().put("type", "minecraft:chest")
                    .put("dimension", "minecraft:overworld").put("x", 12).put("y", 64).put("z", -4)
                    .put("verified", true);

            repository.rememberObservedContainers("c1", status);
            var world = repository.enrichVerifiedWorld("c1", Json.object().put("bodyState", "spawned"));

            assertEquals(1, world.path("knownContainers").size());
            assertEquals(12, world.path("knownContainers").get(0).path("x").asInt());
            assertFalse(world.path("knownContainers").get(0).has("contents"));
            assertEquals(1, repository.verifiedLandmarkKeys("c1").size());
        }
    }

    @Test
    void preferenceContextCarriesValueAndFreshnessMetadata() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("preference.db"))) {
            database.initialize();
            MemoryRepository repository = new MemoryRepository(database);
            repository.remember("c1", MemoryKind.PREFERENCE, "risk",
                    Json.object().put("riskPreference", "LOW"), true, 1.0, null);

            var context = repository.preferenceContext("c1", 10);

            assertEquals(1, context.size());
            assertEquals("risk", context.path(0).path("key").asText());
            assertEquals("LOW", context.path(0).path("value").path("riskPreference").asText());
            assertTrue(context.path(0).path("verified").asBoolean());
            assertFalse(context.path(0).path("updatedAt").asText().isBlank());
        }
    }

    @Test
    void supportsProvenanceSearchDeleteAndClear() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("crud.db"))) {
            database.initialize();
            MemoryRepository repository = new MemoryRepository(database);
            MemoryFact first = repository.remember("c1", MemoryKind.EPISODIC, "trip:first",
                    Json.object().put("summary", "found a village"), true, 1.0, null, "USER");
            repository.remember("c1", MemoryKind.PREFERENCE, "style", Json.object().put("value", "quiet"),
                    false, 0.5, null, "EXTERNAL_BRAIN_SUGGESTION");
            assertEquals("USER", repository.search("c1", "village", 10).getFirst().source());
            assertTrue(repository.delete("c1", first.memoryId()));
            assertEquals(1, repository.clear("c1", MemoryKind.PREFERENCE));
            assertTrue(repository.search("c1", "quiet", 10).isEmpty());
        }
    }

    @Test
    void quarantinedSuggestionsNeverAppearAsMemoryFacts() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("suggestions.db"))) {
            database.initialize();
            MemoryRepository repository = new MemoryRepository(database);

            MemorySuggestion suggestion = repository.suggest("c1", MemoryKind.WORLD, "landmark:claimed",
                    Json.object().put("dimension", "examplemod:moon"), 0.5,
                    Duration.ofDays(30), "EXTERNAL_BRAIN_SUGGESTION", "brain-1");

            assertEquals("QUARANTINED", suggestion.status());
            assertEquals("brain-1", suggestion.brainSessionId());
            assertTrue(repository.relevant("c1", MemoryKind.WORLD, 100).isEmpty());
            assertTrue(repository.search("c1", "moon", 10).isEmpty());
            assertEquals(suggestion.suggestionId(),
                    repository.suggestions("c1", "QUARANTINED", 10).getFirst().suggestionId());
        }
    }

    @Test
    void localReviewAtomicallyPromotesOrRejectsWithinCompanionScope() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("review.db"))) {
            database.initialize();
            MemoryRepository repository = new MemoryRepository(database);
            MemorySuggestion approved = repository.suggest("c1", MemoryKind.WORLD, "landmark:moon",
                    Json.object().put("dimension", "examplemod:moon"), 0.5,
                    Duration.ofDays(30), "EXTERNAL_BRAIN_SUGGESTION", "brain-1");
            MemorySuggestion rejected = repository.suggest("c1", MemoryKind.PREFERENCE, "style",
                    Json.object().put("value", "quiet"), 0.4,
                    Duration.ofDays(30), "EXTERNAL_BRAIN_SUGGESTION", "brain-1");

            assertThrows(IllegalArgumentException.class,
                    () -> repository.approveSuggestion("c2", approved.suggestionId(), "LOCAL_MANAGEMENT_USER"));
            MemoryFact fact = repository.approveSuggestion(
                    "c1", approved.suggestionId(), "LOCAL_MANAGEMENT_USER");
            MemorySuggestion rejection = repository.rejectSuggestion(
                    "c1", rejected.suggestionId(), "LOCAL_MANAGEMENT_USER", "not observed");

            assertTrue(fact.verified());
            assertEquals("USER_APPROVED_SUGGESTION", fact.source());
            assertEquals("APPROVED", repository.suggestions("c1", "APPROVED", 10).getFirst().status());
            assertEquals("LOCAL_MANAGEMENT_USER",
                    repository.suggestions("c1", "APPROVED", 10).getFirst().reviewedBy());
            assertEquals("REJECTED", rejection.status());
            assertEquals("not observed", rejection.reviewReason());
            assertThrows(IllegalStateException.class,
                    () -> repository.approveSuggestion("c1", approved.suggestionId(), "LOCAL_MANAGEMENT_USER"));
            assertEquals(1, repository.relevant("c1", MemoryKind.WORLD, 10).size());
            assertTrue(repository.relevant("c1", MemoryKind.PREFERENCE, 10).isEmpty());
        }
    }

    @Test
    void workingMemoryRequiresBoundedTtlAndEvictsOldestAtCapacity() throws Exception {
        Instant now = Instant.parse("2026-07-15T00:00:00Z");
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("working-capacity.db"))) {
            database.initialize();
            MemoryRepository repository = new MemoryRepository(database, Clock.fixed(now, ZoneOffset.UTC));
            assertThrows(IllegalArgumentException.class, () -> repository.remember(
                    "c1", MemoryKind.WORKING, "permanent", Json.object(), false, 0.5, null));
            assertThrows(IllegalArgumentException.class, () -> repository.remember(
                    "c1", MemoryKind.WORKING, "too-long", Json.object(), false, 0.5,
                    Duration.ofHours(25)));
            for (int index = 0; index < 129; index++) {
                MemoryRepository writer = new MemoryRepository(database,
                        Clock.fixed(now.plusSeconds(index), ZoneOffset.UTC));
                writer.remember("c1", MemoryKind.WORKING, "working:" + index,
                        Json.object().put("index", index), false, 0.5, Duration.ofHours(1));
            }
            MemoryRepository reader = new MemoryRepository(database,
                    Clock.fixed(now.plusSeconds(129), ZoneOffset.UTC));
            var retained = reader.relevant("c1", MemoryKind.WORKING, 200);
            assertEquals(100, retained.size(), "read APIs retain their independent 100 row bound");
            assertTrue(reader.search("c1", MemoryKind.WORKING, "working:0", 10).isEmpty());
            assertEquals(1, reader.search("c1", MemoryKind.WORKING, "working:128", 10).size());
        }
    }

    @Test
    void durableMemoryCapacityFailsClosedInsteadOfDeletingVerifiedFacts() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("durable-capacity.db"))) {
            database.initialize();
            MemoryRepository repository = new MemoryRepository(database);
            for (int index = 0; index < 128; index++) {
                repository.remember("c1", MemoryKind.PREFERENCE, "preference:" + index,
                        Json.object().put("index", index), true, 1.0, null, "USER");
            }
            assertThrows(IllegalStateException.class, () -> repository.remember(
                    "c1", MemoryKind.PREFERENCE, "overflow", Json.object(), true, 1.0, null, "USER"));
            MemoryFact retained = repository.remember("c1", MemoryKind.PREFERENCE, "preference:0",
                    Json.object().put("index", -1), false, 0.1, null, "INFERENCE");
            assertTrue(retained.verified());
            assertEquals(0, retained.value().path("index").asInt());
        }
    }
}
