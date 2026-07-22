package com.mccompanion.runtime.memory;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class EpisodicCandidateReviewTest {
    @TempDir Path temporary;

    @Test
    void capsuleCandidateStaysQuarantinedShowsConflictAndRequiresLocalReview() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("candidate.db"))) {
            database.initialize();
            EpisodeCapsuleRepositoryTest.seedEpisode(database, "c1", "brain-1");
            MemoryRepository memories = new MemoryRepository(database);
            EpisodeCapsule capsule = memories.capsules().generate("c1", "brain-1", "e93c86b");
            memories.remember("c1", MemoryKind.WORLD, "route:mine", Json.object().put("safe", false),
                    true, 1.0, null, "USER");

            MemorySuggestion candidate = memories.suggest("c1", MemoryKind.WORLD, "route:mine",
                    Json.object().put("safe", true), 0.7, Duration.ofDays(30),
                    "EXTERNAL_BRAIN_SUGGESTION", "brain-1", capsule.episodeId());

            assertEquals("QUARANTINED", candidate.status());
            assertEquals("EPISODE_CAPSULE", candidate.source());
            assertEquals(capsule.episodeId(), candidate.capsuleId());
            assertTrue(candidate.conflictsWithVerified());
            assertNotNull(candidate.conflictingMemoryId());
            assertEquals(false, memories.search("c1", MemoryKind.WORLD, "route:mine", 10)
                    .getFirst().value().path("safe").asBoolean());
            assertThrows(IllegalArgumentException.class, () -> memories.suggest("c1", MemoryKind.EPISODIC,
                    "episode:bad", Json.object(), 0.5, Duration.ofDays(1), "EXTERNAL_BRAIN_SUGGESTION",
                    "other-session", capsule.episodeId()));

            MemoryFact approved = memories.approveSuggestion("c1", candidate.suggestionId(), "LOCAL_MANAGEMENT_USER");
            assertTrue(approved.verified());
            assertTrue(approved.source().startsWith("USER_APPROVED_EPISODE_CAPSULE:"));
            assertEquals(true, approved.value().path("safe").asBoolean());

            MemorySuggestion rejectedCandidate = memories.suggest("c1", MemoryKind.EPISODIC, "episode:summary",
                    Json.object().put("result", "returned safely"), 0.6, Duration.ofDays(30),
                    "EXTERNAL_BRAIN_SUGGESTION", "brain-1", capsule.episodeId());
            memories.rejectSuggestion("c1", rejectedCandidate.suggestionId(), "LOCAL_MANAGEMENT_USER", "not durable");
            assertTrue(memories.search("c1", MemoryKind.EPISODIC, "episode:summary", 10).isEmpty());

            MemorySuggestion expiring = memories.suggest("c1", MemoryKind.EPISODIC, "episode:expires",
                    Json.object().put("result", "temporary"), 0.4, Duration.ofMinutes(1),
                    "EXTERNAL_BRAIN_SUGGESTION", "brain-1", capsule.episodeId());
            try (var connection = database.open(); var statement = connection.prepareStatement(
                    "UPDATE memory_suggestion SET expires_at=0 WHERE suggestion_id=?")) {
                statement.setString(1, expiring.suggestionId()); statement.executeUpdate();
            }
            assertEquals(1, memories.expire());
            try (var connection = database.open(); var statement = connection.prepareStatement("""
                    SELECT status,reviewed_by,review_reason,reviewed_at FROM memory_suggestion WHERE suggestion_id=?
                    """)) {
                statement.setString(1, expiring.suggestionId());
                try (var row = statement.executeQuery()) {
                    assertTrue(row.next()); assertEquals("EXPIRED", row.getString(1));
                    assertEquals("SYSTEM_TTL", row.getString(2)); assertEquals("TTL_EXPIRED", row.getString(3));
                    assertNotNull(row.getObject(4));
                }
            }
        }
    }
}
