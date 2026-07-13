package com.mccompanion.runtime.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RuntimeDatabase implements AutoCloseable {
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "runtime_session", "companion", "control_lease", "task", "task_event",
            "behavior_run", "action_evidence", "schema_migration");

    private final Path path;
    private final String jdbcUrl;
    private final List<Migration> migrations;
    private final boolean validateRuntimeSchema;
    private final Clock clock;

    public RuntimeDatabase(Path path) {
        this(path, defaultMigrations(), true, Clock.systemUTC());
    }

    public RuntimeDatabase(Path path, List<Migration> migrations) {
        this(path, migrations, false, Clock.systemUTC());
    }

    RuntimeDatabase(Path path, List<Migration> migrations, boolean validateRuntimeSchema, Clock clock) {
        this.path = path.toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:sqlite:" + this.path;
        this.migrations = List.copyOf(migrations);
        this.validateRuntimeSchema = validateRuntimeSchema;
        this.clock = clock;
    }

    public synchronized void initialize() throws SQLException, IOException {
        validateMigrationPlan();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException("Database path must not be a symbolic link");
        }
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            // journal_mode is persistent database state and takes a database lock when it is
            // changed (or re-asserted by some SQLite builds). Configure it once before the
            // Runtime starts concurrent command, WebSocket and lease-sweep work. Repeating
            // this PRAGMA from every open() can otherwise surface SQLITE_BUSY even though
            // busy_timeout is configured for ordinary statements.
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migration (
                      version INTEGER PRIMARY KEY,
                      description TEXT NOT NULL,
                      applied_at INTEGER NOT NULL
                    )
                    """);
        }
        int current = currentVersion();
        int latestSupported = migrations.isEmpty() ? 0 : migrations.get(migrations.size() - 1).version();
        if (current > latestSupported) {
            throw new SQLException("Database schema version " + current
                    + " is newer than the supported version " + latestSupported);
        }
        for (Migration migration : migrations) {
            if (migration.version() <= current) {
                continue;
            }
            apply(migration);
        }
        if (validateRuntimeSchema) {
            validateSchema();
        }
    }

    private void validateMigrationPlan() throws SQLException {
        int expected = 1;
        for (Migration migration : migrations) {
            if (migration.version() != expected) {
                throw new SQLException("Migrations must be contiguous and ordered; expected version " + expected
                        + " but found " + migration.version());
            }
            expected++;
        }
    }

    private void apply(Migration migration) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                for (String sql : migration.statements()) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO schema_migration(version, description, applied_at) VALUES (?, ?, ?)")) {
                    statement.setInt(1, migration.version());
                    statement.setString(2, migration.description());
                    statement.setLong(3, clock.millis());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=NORMAL");
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    public int currentVersion() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migration")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    public String journalMode() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
            return result.next() ? result.getString(1) : "";
        }
    }

    private void validateSchema() throws SQLException {
        Set<String> present = new java.util.HashSet<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table'"); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                present.add(result.getString(1));
            }
        }
        Set<String> missing = new java.util.HashSet<>(REQUIRED_TABLES);
        missing.removeAll(present);
        if (!missing.isEmpty()) {
            throw new SQLException("Runtime schema validation failed; missing tables: " + missing);
        }
    }

    public void checkpoint() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    @Override
    public void close() throws SQLException {
        checkpoint();
    }

    public record Migration(int version, String description, List<String> statements) {
        public Migration {
            if (version <= 0) {
                throw new IllegalArgumentException("Migration version must be positive");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Migration description must not be blank");
            }
            description = description.trim();
            statements = List.copyOf(statements);
            if (statements.isEmpty() || statements.stream().anyMatch(sql -> sql == null || sql.isBlank())) {
                throw new IllegalArgumentException("Migration must contain non-blank SQL statements");
            }
        }
    }

    public static List<Migration> defaultMigrations() {
        List<String> statements = new ArrayList<>();
        statements.add("""
                CREATE TABLE runtime_session (
                  session_id TEXT PRIMARY KEY,
                  world_id TEXT NOT NULL,
                  protocol TEXT NOT NULL,
                  mod_version TEXT NOT NULL,
                  minecraft_version TEXT NOT NULL,
                  loader TEXT NOT NULL,
                  capabilities_json TEXT NOT NULL,
                  state TEXT NOT NULL,
                  connected_at INTEGER NOT NULL,
                  last_seen_at INTEGER NOT NULL,
                  disconnected_at INTEGER
                )
                """);
        statements.add("CREATE INDEX runtime_session_world_idx ON runtime_session(world_id, state)");
        statements.add("""
                CREATE TABLE companion (
                  companion_id TEXT PRIMARY KEY,
                  session_id TEXT,
                  world_id TEXT NOT NULL,
                  owner_id TEXT,
                  display_name TEXT NOT NULL,
                  status_json TEXT NOT NULL,
                  last_seen_at INTEGER NOT NULL
                )
                """);
        statements.add("CREATE INDEX companion_world_idx ON companion(world_id)");
        statements.add("""
                CREATE TABLE control_epoch (
                  companion_id TEXT PRIMARY KEY,
                  last_epoch INTEGER NOT NULL
                )
                """);
        statements.add("""
                CREATE TABLE control_lease (
                  companion_id TEXT PRIMARY KEY,
                  controller_id TEXT NOT NULL,
                  epoch INTEGER NOT NULL,
                  lease_token_hash TEXT NOT NULL,
                  mode TEXT NOT NULL,
                  expires_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """);
        statements.add("CREATE INDEX control_lease_expiry_idx ON control_lease(expires_at)");
        statements.add("""
                CREATE TABLE task (
                  task_id TEXT PRIMARY KEY,
                  root_task_id TEXT NOT NULL,
                  parent_task_id TEXT,
                  companion_id TEXT NOT NULL,
                  task_type TEXT NOT NULL,
                  state TEXT NOT NULL,
                  revision INTEGER NOT NULL,
                  request_text TEXT NOT NULL,
                  payload_json TEXT NOT NULL,
                  behavior_id TEXT,
                  behavior_revision INTEGER NOT NULL DEFAULT 0,
                  reconciliation_required INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """);
        statements.add("CREATE INDEX task_companion_state_idx ON task(companion_id, state)");
        statements.add("""
                CREATE TABLE task_event (
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  task_id TEXT NOT NULL,
                  revision INTEGER NOT NULL,
                  event_type TEXT NOT NULL,
                  payload_json TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  FOREIGN KEY(task_id) REFERENCES task(task_id) ON DELETE CASCADE
                )
                """);
        statements.add("CREATE UNIQUE INDEX task_event_revision_idx ON task_event(task_id, revision)");
        statements.add("""
                CREATE TABLE task_snapshot (
                  task_id TEXT PRIMARY KEY,
                  revision INTEGER NOT NULL,
                  snapshot_json TEXT NOT NULL,
                  updated_at INTEGER NOT NULL,
                  FOREIGN KEY(task_id) REFERENCES task(task_id) ON DELETE CASCADE
                )
                """);
        statements.add("""
                CREATE TABLE behavior_run (
                  behavior_id TEXT PRIMARY KEY,
                  task_id TEXT NOT NULL,
                  companion_id TEXT NOT NULL,
                  behavior_type TEXT NOT NULL,
                  revision INTEGER NOT NULL,
                  state TEXT NOT NULL,
                  started_at INTEGER,
                  updated_at INTEGER NOT NULL,
                  completed_at INTEGER,
                  failure_code TEXT,
                  FOREIGN KEY(task_id) REFERENCES task(task_id) ON DELETE CASCADE
                )
                """);
        statements.add("""
                CREATE TABLE action_evidence (
                  evidence_id TEXT PRIMARY KEY,
                  task_id TEXT NOT NULL,
                  behavior_id TEXT,
                  action_type TEXT NOT NULL,
                  before_json TEXT NOT NULL,
                  after_json TEXT NOT NULL,
                  forbidden_write_detected INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL,
                  FOREIGN KEY(task_id) REFERENCES task(task_id) ON DELETE CASCADE
                )
                """);
        statements.add("""
                CREATE TABLE command_result (
                  command_id TEXT PRIMARY KEY,
                  request_hash TEXT NOT NULL,
                  state TEXT NOT NULL,
                  response_json TEXT,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """);
        statements.add("CREATE INDEX command_result_created_idx ON command_result(created_at)");
        List<String> taskSafety = new ArrayList<>();
        taskSafety.add("""
                CREATE TABLE task_command (
                  command_id TEXT PRIMARY KEY,
                  task_id TEXT NOT NULL,
                  command_type TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  FOREIGN KEY(task_id) REFERENCES task(task_id) ON DELETE CASCADE
                )
                """);
        taskSafety.add("CREATE INDEX task_command_task_idx ON task_command(task_id, created_at)");
        taskSafety.add("""
                CREATE UNIQUE INDEX task_one_active_idx ON task(companion_id)
                WHERE state NOT IN ('COMPLETED','FAILED','CANCELLED')
                """);
        List<String> epochTracking = List.of(
                "ALTER TABLE task ADD COLUMN control_epoch INTEGER NOT NULL DEFAULT 0");
        return List.of(
                new Migration(1, "initial runtime schema", statements),
                new Migration(2, "durable command correlation and single active task", taskSafety),
                new Migration(3, "persist task control epochs", epochTracking));
    }
}
