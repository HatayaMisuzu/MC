package com.mccompanion.runtime.health;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class McpSessionRepositoryTest {
    @TempDir Path temporary;

    @Test
    void persistsOnlyHashAndEnforcesScopeVersionTerminationAndExpiry() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("runtime.db"))) {
            database.initialize();
            Clock initial = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);
            McpSessionRepository sessions = new McpSessionRepository(
                    database, initial, Duration.ofHours(1), new SecureRandom());
            ToolContext owner = new ToolContext("hermes", "brain-a", "companion-a");
            String token = sessions.create(owner, "2025-06-18");

            assertEquals(43, token.length());
            assertEquals(McpSessionRepository.Status.ACTIVE,
                    sessions.validate(token, owner, "2025-06-18"));
            assertEquals(McpSessionRepository.Status.NOT_FOUND, sessions.validate(token,
                    new ToolContext("hermes", "brain-a", "companion-b"), "2025-06-18"));
            assertEquals(McpSessionRepository.Status.NOT_FOUND,
                    sessions.validate(token, owner, "2025-03-26"));
            assertEquals(McpSessionRepository.Status.NOT_FOUND,
                    sessions.validate("not-a-valid-session", owner, "2025-06-18"));
            try (var connection = database.open(); var statement = connection.prepareStatement(
                    "SELECT session_hash FROM mcp_session WHERE session_hash=?")) {
                statement.setString(1, token);
                assertFalse(statement.executeQuery().next(), "raw bearer token must not be persisted");
            }
            assertTrue(sessions.terminate(token, owner, "2025-06-18"));
            assertEquals(McpSessionRepository.Status.NOT_FOUND,
                    sessions.validate(token, owner, "2025-06-18"));

            String expiring = sessions.create(owner, "2025-06-18");
            Clock later = Clock.fixed(Instant.parse("2026-07-18T02:00:00Z"), ZoneOffset.UTC);
            McpSessionRepository restarted = new McpSessionRepository(
                    database, later, Duration.ofHours(1), new SecureRandom());
            assertEquals(McpSessionRepository.Status.NOT_FOUND,
                    restarted.validate(expiring, owner, "2025-06-18"));
            assertEquals(1, restarted.expire());
        }
    }
}
