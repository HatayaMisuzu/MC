package com.mccompanion.runtime.health;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class McpReplayRepositoryTest {
    @TempDir Path temporary;

    @Test
    void bindsIdentityReplaysTerminalAndQuarantinesInterruptedRequestsAcrossRestart() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("runtime.db"))) {
            database.initialize();
            McpReplayRepository repository = new McpReplayRepository(database);
            ToolCall original = new ToolCall("mcp-one", "observe.state",
                    Json.object().put("detail", "summary"));

            assertEquals(McpReplayRepository.Status.NEW, repository.acquire(original).status());
            assertEquals(McpReplayRepository.Status.IN_FLIGHT, repository.acquire(original).status());
            assertEquals(McpReplayRepository.Status.CONFLICT, repository.acquire(
                    new ToolCall("mcp-one", "observe.state", Json.object().put("detail", "full"))).status());
            assertEquals(McpReplayRepository.Status.CONFLICT, repository.acquire(
                    new ToolCall("mcp-one", "memory.query", original.arguments())).status());

            ToolResult terminal = new ToolResult(original.callId(), original.name(), true, "OBSERVED",
                    Json.object().put("health", 20), true);
            repository.complete(original, terminal);

            McpReplayRepository restarted = new McpReplayRepository(database);
            McpReplayRepository.Acquisition replay = restarted.acquire(original);
            assertEquals(McpReplayRepository.Status.TERMINAL, replay.status());
            assertEquals(terminal, replay.result());

            ToolCall interrupted = new ToolCall("mcp-two", "observe.state", Json.object());
            assertEquals(McpReplayRepository.Status.NEW, repository.acquire(interrupted).status());
            assertEquals(1, restarted.quarantineInterrupted());
            assertEquals(McpReplayRepository.Status.RECONCILIATION_REQUIRED,
                    repository.acquire(interrupted).status());
            assertThrows(java.sql.SQLException.class, () -> repository.complete(interrupted,
                    ToolResult.rejected(interrupted, "CANCELLED", "late result")));
        }
    }
}
