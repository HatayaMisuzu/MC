package com.mccompanion.runtime.search;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchSessionRepositoryTest {
    @TempDir Path temporary;

    @Test
    void persistsSourcesAcrossRestartAndEnforcesExactScopeSupersessionCancelAndExpiry() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("runtime.db"))) {
            database.initialize();
            Clock initial = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);
            SearchSessionRepository sessions = new SearchSessionRepository(database, initial);
            ToolContext owner = new ToolContext("hermes", "brain-a", "companion-a");
            SearchQuery policy = new SearchQuery("Fabric documentation", List.of("fabricmc.net"),
                    3, 30, "en", true, Duration.ofSeconds(10));
            SearchSource source = new SearchSource("docs-1", "Fabric docs",
                    "https://docs.fabricmc.net/", "docs.fabricmc.net", "Fabric", null,
                    initial.instant(), "Untrusted snippet", "EXTERNAL", "text/html");

            sessions.activate("search-1", owner, policy, List.of(source));
            SearchSessionRepository restarted = new SearchSessionRepository(database, initial);
            var restored = restarted.active(owner).orElseThrow();
            assertEquals("search-1", restored.searchId());
            assertEquals(policy, restored.policy());
            assertEquals(List.of(source), restored.sources());
            assertTrue(restarted.active(new ToolContext("hermes", "brain-a", "companion-b")).isEmpty());
            assertTrue(restarted.active(new ToolContext("other", "brain-a", "companion-a")).isEmpty());

            restarted.activate("search-2", owner, policy, List.of(source));
            assertEquals("search-2", sessions.active(owner).orElseThrow().searchId());
            assertEquals("search-2", sessions.cancel(owner).orElseThrow());
            assertTrue(sessions.active(owner).isEmpty());

            sessions.activate("search-expiring", owner, policy, List.of(source));
            SearchSessionRepository later = new SearchSessionRepository(database,
                    Clock.fixed(initial.instant().plus(Duration.ofMinutes(6)), ZoneOffset.UTC));
            assertEquals(1, later.expire());
            assertTrue(later.active(owner).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> sessions.activate("oversized", owner,
                    policy, List.of(new SearchSource("huge", "Title", "https://example.com",
                            "example.com", "Publisher", null, initial.instant(),
                            "x".repeat(SearchSessionRepository.MAX_STATE_BYTES),
                            "EXTERNAL", "text/plain"))));

            for (int index = 0; index < 130; index++) {
                sessions.activate("bounded-" + index,
                        new ToolContext("hermes", "brain-" + index, "bounded-companion"),
                        policy, List.of(source));
            }
            try (var connection = database.open(); var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM search_session WHERE companion_id='bounded-companion'");
                 var row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(SearchSessionRepository.MAX_SESSIONS_PER_COMPANION, row.getInt(1));
            }
            assertTrue(sessions.active(
                    new ToolContext("hermes", "brain-0", "bounded-companion")).isEmpty());
        }
    }
}
