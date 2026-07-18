package com.mccompanion.runtime.health;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class McpEventRepositoryTest {
    @TempDir Path temporary;

    @Test
    void replaysOnlyEventsAfterSameSessionCursorAndBoundsRetentionAndPayload() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("runtime.db"))) {
            database.initialize();
            McpEventRepository events = new McpEventRepository(database);
            String session = "A".repeat(43);
            McpEventRepository.Event cursor = events.append(
                    session, "call-0", Json.object().put("index", 0), false);
            McpEventRepository.Event next = events.append(
                    session, "call-1", Json.object().put("index", 1), true);

            McpEventRepository restarted = new McpEventRepository(database);
            var replay = restarted.after(session, cursor.eventId());
            assertEquals(1, replay.size());
            assertEquals(next.eventId(), replay.getFirst().eventId());
            assertTrue(replay.getFirst().terminal());
            assertThrows(IllegalArgumentException.class,
                    () -> events.after("B".repeat(43), cursor.eventId()));

            McpEventRepository.Event retainedCursor = next;
            for (int index = 2; index < 302; index++) {
                events.append(session, "call-" + index, Json.object().put("index", index), false);
            }
            try (var connection = database.open(); var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM mcp_event WHERE session_hash=?")) {
                statement.setString(1, com.mccompanion.runtime.security.Digests.sha256(session));
                try (var row = statement.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(McpEventRepository.MAX_EVENTS_PER_SESSION, row.getInt(1));
                }
            }
            assertThrows(IllegalArgumentException.class,
                    () -> events.after(session, retainedCursor.eventId()), "pruned cursor must fail closed");
            assertThrows(IllegalArgumentException.class, () -> events.append(session, "oversized",
                    Json.object().put("content", "x".repeat(McpEventRepository.MAX_EVENT_BYTES)), false));
            assertEquals(McpEventRepository.MAX_EVENTS_PER_SESSION, events.deleteSession(session));
            assertThrows(IllegalArgumentException.class, () -> events.after(session, cursor.eventId()));

            McpSessionRepository sessions = new McpSessionRepository(database);
            ToolContext owner = new ToolContext("hermes", "brain", "companion");
            String activeSession = sessions.create(owner, "2025-06-18");
            events.append(activeSession, "active", Json.object().put("state", "active"), false);
            assertEquals(0, events.pruneInactiveSessions());
            assertTrue(sessions.terminate(activeSession, owner, "2025-06-18"));
            assertEquals(1, events.pruneInactiveSessions());
        }
    }
}
