package com.mccompanion.runtime.memory;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryToolGatewayTest {
    @TempDir Path temporary;

    @Test
    void brainMayReadAndSuggestPreferencesButCannotWriteVerifiedWorldFacts() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("memory-tools.db"))) {
            database.initialize();
            MemoryRepository repository = new MemoryRepository(database);
            repository.remember("c1", MemoryKind.WORLD, "landmark:home", Json.object().put("name", "Home"),
                    true, 1.0, null, "USER");
            MemoryToolGateway gateway = new MemoryToolGateway(repository);
            ToolContext context = new ToolContext("controller", "brain-session", "c1");
            assertEquals(java.util.List.of("memory.list", "memory.search", "memory.suggest_preference"),
                    gateway.definitions(context).stream().map(value -> value.name()).toList());

            var listed = gateway.execute(context, new ToolCall("l1", "memory.list",
                    Json.object().put("kind", "WORLD")));
            assertTrue(listed.success());
            assertEquals("USER", listed.observation().path(0).path("source").asText());

            var suggestion = gateway.execute(context, new ToolCall("p1", "memory.suggest_preference",
                    Json.object().put("key", "reply_style").put("value", "concise").put("confidence", 0.7)));
            assertTrue(suggestion.success());
            assertFalse(suggestion.observation().path("verified").asBoolean());
            assertEquals("EXTERNAL_BRAIN_SUGGESTION", suggestion.observation().path("source").asText());
            assertEquals(MemoryKind.PREFERENCE, repository.search("c1", "concise", 10).getFirst().kind());

            assertTrue(gateway.execute(context, new ToolCall("w1", "memory.write_world", Json.object())).code()
                    .equals("TOOL_UNAVAILABLE"));
        }
    }

    @Test
    void sensitivePreferenceSuggestionIsRejected() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("sensitive.db"))) {
            database.initialize();
            MemoryToolGateway gateway = new MemoryToolGateway(new MemoryRepository(database));
            var result = gateway.execute(new ToolContext("controller", "brain-session", "c1"),
                    new ToolCall("p1", "memory.suggest_preference", Json.object()
                            .put("key", "secret").put("value", "C:\\Users\\Player\\token.txt")));
            assertFalse(result.success());
            assertEquals("INVALID_TOOL_ARGUMENTS", result.code());
        }
    }
}
