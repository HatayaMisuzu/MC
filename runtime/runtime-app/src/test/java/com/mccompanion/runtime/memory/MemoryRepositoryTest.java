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
}
