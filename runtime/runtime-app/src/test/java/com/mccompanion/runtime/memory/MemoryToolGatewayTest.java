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
            assertEquals(java.util.List.of("world.locate_known_container", "memory.list", "memory.search",
                            "memory.episode_capsules", "memory.suggest", "memory.suggest_preference"),
                    gateway.definitions(context).stream().map(value -> value.name()).toList());

            var listed = gateway.execute(context, new ToolCall("l1", "memory.list",
                    Json.object().put("kind", "WORLD")));
            assertTrue(listed.success());
            assertEquals("USER", listed.observation().path(0).path("source").asText());

            var suggestion = gateway.execute(context, new ToolCall("p1", "memory.suggest_preference",
                    Json.object().put("key", "reply_style").put("value", "concise").put("confidence", 0.7)));
            assertTrue(suggestion.success());
            assertEquals("MEMORY_SUGGESTION_QUARANTINED", suggestion.code());
            assertEquals("QUARANTINED", suggestion.observation().path("status").asText());
            assertEquals("EXTERNAL_BRAIN_SUGGESTION", suggestion.observation().path("source").asText());
            assertTrue(repository.search("c1", "concise", 10).isEmpty());
            assertEquals(MemoryKind.PREFERENCE,
                    repository.suggestions("c1", "QUARANTINED", 10).getFirst().kind());

            var worldSuggestion = gateway.execute(context, new ToolCall("w-suggest", "memory.suggest",
                    Json.object().put("kind", "WORLD").put("key", "landmark:untrusted")
                            .set("value", Json.object().put("dimension", "examplemod:moon")
                                    .put("x", 1).put("y", 2).put("z", 3))));
            assertTrue(worldSuggestion.success());
            assertEquals("QUARANTINED", worldSuggestion.observation().path("status").asText());
            assertTrue(repository.relevant("c1", MemoryKind.WORLD, 100).stream()
                    .noneMatch(value -> value.key().equals("landmark:untrusted")));

            repository.remember("c1", MemoryKind.WORLD, "ore:iron",
                    Json.object().put("dimension", "minecraft:overworld"), true, 1.0, null,
                    "BODY_OBSERVATION");
            var filtered = gateway.execute(context, new ToolCall("s1", "memory.search",
                    Json.object().put("kind", "PREFERENCE").put("query", "ore").put("limit", 25)));
            assertTrue(filtered.success());
            assertTrue(filtered.observation().isEmpty(), filtered.observation().toString());

            assertTrue(gateway.execute(context, new ToolCall("w1", "memory.write_world", Json.object())).code()
                    .equals("TOOL_UNAVAILABLE"));
        }
    }

    @Test
    void locateKnownContainerReturnsOnlyVerifiedCandidatesAndMarksCrossDimension() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("containers.db"))) {
            database.initialize();
            MemoryRepository repository = new MemoryRepository(database);
            repository.remember("c1", MemoryKind.WORLD, "container:minecraft:overworld:1:64:2",
                    Json.object().put("dimension", "minecraft:overworld").put("x", 1).put("y", 64).put("z", 2)
                            .put("type", "minecraft:chest"), true, 1.0, null, "BODY_OBSERVATION");
            repository.remember("c1", MemoryKind.WORLD, "container:minecraft:the_nether:3:70:4",
                    Json.object().put("dimension", "minecraft:the_nether").put("x", 3).put("y", 70).put("z", 4),
                    true, 1.0, null, "BODY_OBSERVATION");
            repository.remember("c1", MemoryKind.WORLD, "container:unverified",
                    Json.object().put("dimension", "minecraft:overworld").put("x", 9).put("y", 64).put("z", 9),
                    false, 0.5, null, "INFERENCE");
            MemoryToolGateway gateway = new MemoryToolGateway(repository);
            var result = gateway.execute(new ToolContext("controller", "brain-session", "c1"),
                    new ToolCall("locate-1", "world.locate_known_container",
                            Json.object().put("dimension", "minecraft:overworld").put("limit", 10)));
            assertTrue(result.success());
            assertEquals(2, result.observation().path("count").asInt());
            assertEquals(1, java.util.stream.StreamSupport.stream(
                    result.observation().path("containers").spliterator(), false)
                    .filter(value -> value.path("sameDimension").asBoolean()).count());
            assertTrue(java.util.stream.StreamSupport.stream(
                    result.observation().path("containers").spliterator(), false)
                    .allMatch(value -> value.path("verified").asBoolean()
                            && value.path("source").asText().equals("BODY_OBSERVATION")));
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

    @Test
    void workingMemorySuggestionIsRejected() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("working-suggestion.db"))) {
            database.initialize();
            MemoryToolGateway gateway = new MemoryToolGateway(new MemoryRepository(database));
            var result = gateway.execute(new ToolContext("controller", "brain-session", "c1"),
                    new ToolCall("working", "memory.suggest", Json.object()
                            .put("kind", "WORKING").put("key", "temporary").put("value", "unsafe")));
            assertFalse(result.success());
            assertEquals("INVALID_TOOL_ARGUMENTS", result.code());
        }
    }
}
