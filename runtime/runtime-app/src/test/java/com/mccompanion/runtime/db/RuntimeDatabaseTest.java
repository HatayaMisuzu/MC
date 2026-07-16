package com.mccompanion.runtime.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeDatabaseTest {
    @TempDir Path temporary;

    @Test
    void createsCompleteWalSchemaAndIsIdempotent() throws Exception {
        Path path = temporary.resolve("runtime.db");
        try (RuntimeDatabase database = new RuntimeDatabase(path)) {
            database.initialize();
            database.initialize();
            assertEquals(10, database.currentVersion());
            assertEquals("wal", database.journalMode().toLowerCase());
            Set<String> tables = new HashSet<>();
            try (var connection = database.open(); Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (result.next()) tables.add(result.getString(1));
            }
            assertTrue(tables.containsAll(Set.of("runtime_session", "companion", "control_lease", "task",
                    "task_event", "behavior_run", "action_evidence", "agent_plan", "agent_step", "agent_plan_revision",
                    "conversation_event", "waiting_question",
                    "memory_fact", "brain_session", "brain_tool_call", "schema_migration")));
        }
    }

    @Test
    void openingConcurrentConnectionDoesNotReconfigureWalDuringActiveWrite() throws Exception {
        Path path = temporary.resolve("concurrent-open.db");
        try (RuntimeDatabase database = new RuntimeDatabase(path)) {
            database.initialize();
            try (Connection writer = database.open(); Statement statement = writer.createStatement()) {
                writer.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO control_epoch(companion_id, last_epoch) VALUES ('held', 1)");

                CompletableFuture<String> opened = CompletableFuture.supplyAsync(() -> {
                    try (Connection concurrent = database.open(); Statement query = concurrent.createStatement();
                         ResultSet result = query.executeQuery("PRAGMA journal_mode")) {
                        return result.next() ? result.getString(1) : "";
                    } catch (SQLException failure) {
                        throw new RuntimeException(failure);
                    }
                });

                assertEquals("wal", opened.get(2, TimeUnit.SECONDS).toLowerCase());
                writer.rollback();
            }
        }
    }

    @Test
    void rollsBackFailedMigrationWithoutRecordingVersion() throws Exception {
        RuntimeDatabase.Migration broken = new RuntimeDatabase.Migration(1, "must rollback",
                List.of("CREATE TABLE transient_table(id INTEGER)", "THIS IS NOT SQL"));
        Path path = temporary.resolve("rollback.db");
        RuntimeDatabase database = new RuntimeDatabase(path, List.of(broken));
        assertThrows(SQLException.class, database::initialize);
        try (var connection = database.open(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='transient_table'")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
        assertEquals(0, database.currentVersion());
    }

    @Test
    void rejectsNonContiguousMigrationPlan() {
        RuntimeDatabase.Migration migration = new RuntimeDatabase.Migration(2, "gap", List.of("SELECT 1"));
        RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("gap.db"), List.of(migration));
        SQLException failure = assertThrows(SQLException.class, database::initialize);
        assertTrue(failure.getMessage().contains("contiguous"));
    }
}
