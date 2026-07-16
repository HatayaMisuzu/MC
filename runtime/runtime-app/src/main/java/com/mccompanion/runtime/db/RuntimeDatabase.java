package com.mccompanion.runtime.db;

import org.sqlite.SQLiteConfig;

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
import java.util.Properties;
import java.util.Set;

public final class RuntimeDatabase implements AutoCloseable {
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "runtime_session", "companion", "control_lease", "task", "task_event",
            "behavior_run", "action_evidence", "agent_plan", "agent_step", "agent_plan_revision",
            "memory_fact", "conversation_event", "waiting_question", "brain_session", "brain_tool_call",
            "task_graph_execution", "schema_migration");

    private final Path path;
    private final String jdbcUrl;
    private final List<Migration> migrations;
    private final boolean validateRuntimeSchema;
    private final Clock clock;
    private final Properties connectionProperties;

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
        SQLiteConfig config = new SQLiteConfig();
        // Runtime repositories commonly validate a row before updating related durable state.
        // Reserve SQLite's single WAL writer before those reads so concurrent writers wait via
        // busy_timeout instead of failing during a deferred read-to-write lock upgrade.
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        this.connectionProperties = config.toProperties();
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
        Properties properties = new Properties();
        properties.putAll(connectionProperties);
        Connection connection = DriverManager.getConnection(jdbcUrl, properties);
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
        List<String> agentKernel = List.of(
                """
                CREATE TABLE agent_plan (
                  plan_id TEXT PRIMARY KEY,
                  companion_id TEXT NOT NULL,
                  request_text TEXT NOT NULL,
                  decision_json TEXT NOT NULL,
                  state TEXT NOT NULL,
                  revision INTEGER NOT NULL,
                  current_step INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """,
                """
                CREATE UNIQUE INDEX agent_plan_one_active_idx ON agent_plan(companion_id)
                WHERE state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
                """,
                """
                CREATE TABLE agent_step (
                  plan_id TEXT NOT NULL,
                  step_index INTEGER NOT NULL,
                  state TEXT NOT NULL,
                  definition_json TEXT NOT NULL,
                  attempt INTEGER NOT NULL DEFAULT 0,
                  task_id TEXT,
                  failure_code TEXT,
                  observation_json TEXT NOT NULL DEFAULT '{}',
                  updated_at INTEGER NOT NULL,
                  PRIMARY KEY(plan_id, step_index),
                  FOREIGN KEY(plan_id) REFERENCES agent_plan(plan_id) ON DELETE CASCADE
                )
                """,
                "CREATE UNIQUE INDEX agent_step_task_idx ON agent_step(task_id) WHERE task_id IS NOT NULL",
                """
                CREATE TABLE memory_fact (
                  memory_id TEXT PRIMARY KEY,
                  companion_id TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  fact_key TEXT NOT NULL,
                  value_json TEXT NOT NULL,
                  verified INTEGER NOT NULL,
                  confidence REAL NOT NULL,
                  expires_at INTEGER,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL,
                  UNIQUE(companion_id, kind, fact_key)
                )
                """,
                "CREATE INDEX memory_fact_lookup_idx ON memory_fact(companion_id, kind, expires_at)");
        List<String> replanning = List.of(
                "ALTER TABLE agent_plan ADD COLUMN planning_revision INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE agent_plan ADD COLUMN replan_count INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE agent_plan ADD COLUMN no_progress_count INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE agent_plan ADD COLUMN plan_fingerprint TEXT NOT NULL DEFAULT ''",
                """
                CREATE TABLE agent_plan_revision (
                  plan_id TEXT NOT NULL,
                  planning_revision INTEGER NOT NULL,
                  decision_json TEXT NOT NULL,
                  trigger_observation_json TEXT NOT NULL,
                  failure_code TEXT,
                  fingerprint TEXT NOT NULL,
                  outcome TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  PRIMARY KEY(plan_id, planning_revision),
                  FOREIGN KEY(plan_id) REFERENCES agent_plan(plan_id) ON DELETE CASCADE
                )
                """,
                "CREATE INDEX agent_plan_revision_lookup_idx ON agent_plan_revision(plan_id, planning_revision)");
        List<String> companionInteraction = List.of(
                "ALTER TABLE agent_plan ADD COLUMN interaction_state TEXT NOT NULL DEFAULT 'ACTIVE'",
                """
                CREATE TABLE conversation_event (
                  event_id TEXT PRIMARY KEY,
                  companion_id TEXT NOT NULL,
                  plan_id TEXT,
                  question_id TEXT,
                  direction TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  content TEXT NOT NULL,
                  payload_json TEXT NOT NULL,
                  game_delivered INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL,
                  FOREIGN KEY(plan_id) REFERENCES agent_plan(plan_id) ON DELETE SET NULL
                )
                """,
                "CREATE INDEX conversation_event_companion_idx ON conversation_event(companion_id,created_at)",
                """
                CREATE TABLE waiting_question (
                  question_id TEXT PRIMARY KEY,
                  plan_id TEXT NOT NULL,
                  companion_id TEXT NOT NULL,
                  prompt TEXT NOT NULL,
                  reason TEXT NOT NULL,
                  options_json TEXT NOT NULL,
                  free_text_allowed INTEGER NOT NULL,
                  state TEXT NOT NULL,
                  context_json TEXT NOT NULL,
                  answer_json TEXT,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL,
                  expires_at INTEGER,
                  FOREIGN KEY(plan_id) REFERENCES agent_plan(plan_id) ON DELETE CASCADE
                )
                """,
                "CREATE UNIQUE INDEX waiting_question_one_active_idx ON waiting_question(plan_id) WHERE state='WAITING'",
                "CREATE INDEX waiting_question_companion_idx ON waiting_question(companion_id,state,updated_at)");
        List<String> memoryProvenance = List.of(
                "ALTER TABLE memory_fact ADD COLUMN source TEXT NOT NULL DEFAULT 'LEGACY'");
        List<String> brainAudit = List.of(
                """
                CREATE TABLE brain_session (
                  session_id TEXT PRIMARY KEY,
                  controller_id TEXT NOT NULL,
                  companion_id TEXT NOT NULL,
                  provider TEXT NOT NULL,
                  state TEXT NOT NULL,
                  last_code TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """,
                "CREATE INDEX brain_session_companion_idx ON brain_session(companion_id,updated_at)",
                """
                CREATE TABLE brain_tool_call (
                  session_id TEXT NOT NULL,
                  call_id TEXT NOT NULL,
                  tool_name TEXT NOT NULL,
                  arguments_json TEXT NOT NULL,
                  success INTEGER NOT NULL,
                  result_code TEXT NOT NULL,
                  observation_json TEXT NOT NULL,
                  terminal INTEGER NOT NULL,
                  created_at INTEGER NOT NULL,
                  PRIMARY KEY(session_id,call_id),
                  FOREIGN KEY(session_id) REFERENCES brain_session(session_id) ON DELETE CASCADE
                )
                """,
                "CREATE INDEX brain_tool_call_session_idx ON brain_tool_call(session_id,created_at)");
        List<String> asynchronousBrainTools = List.of(
                "ALTER TABLE brain_tool_call ADD COLUMN state TEXT NOT NULL DEFAULT 'INTERRUPTED'",
                "ALTER TABLE brain_tool_call ADD COLUMN task_id TEXT",
                "ALTER TABLE brain_tool_call ADD COLUMN behavior_id TEXT",
                "ALTER TABLE brain_tool_call ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE brain_tool_call ADD COLUMN delivered_at INTEGER",
                "CREATE INDEX brain_tool_call_task_idx ON brain_tool_call(task_id)");
        List<String> externalBrainQuestions = List.of(
                "ALTER TABLE waiting_question RENAME TO waiting_question_legacy",
                """
                CREATE TABLE waiting_question (
                  question_id TEXT PRIMARY KEY,
                  plan_id TEXT,
                  brain_session_id TEXT,
                  task_id TEXT,
                  companion_id TEXT NOT NULL,
                  prompt TEXT NOT NULL,
                  reason TEXT NOT NULL,
                  options_json TEXT NOT NULL,
                  free_text_allowed INTEGER NOT NULL,
                  state TEXT NOT NULL,
                  context_json TEXT NOT NULL,
                  answer_json TEXT,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL,
                  expires_at INTEGER,
                  CHECK ((plan_id IS NOT NULL) <> (brain_session_id IS NOT NULL)),
                  FOREIGN KEY(plan_id) REFERENCES agent_plan(plan_id) ON DELETE CASCADE,
                  FOREIGN KEY(brain_session_id) REFERENCES brain_session(session_id) ON DELETE CASCADE,
                  FOREIGN KEY(task_id) REFERENCES task(task_id) ON DELETE SET NULL
                )
                """,
                """
                INSERT INTO waiting_question(question_id,plan_id,brain_session_id,task_id,companion_id,prompt,
                  reason,options_json,free_text_allowed,state,context_json,answer_json,created_at,updated_at,expires_at)
                SELECT question_id,plan_id,NULL,NULL,companion_id,prompt,reason,options_json,free_text_allowed,state,
                  context_json,answer_json,created_at,updated_at,expires_at FROM waiting_question_legacy
                """,
                "DROP TABLE waiting_question_legacy",
                "CREATE UNIQUE INDEX waiting_question_one_active_idx ON waiting_question(plan_id) WHERE state='WAITING' AND plan_id IS NOT NULL",
                "CREATE UNIQUE INDEX waiting_question_one_brain_active_idx ON waiting_question(brain_session_id) WHERE state='WAITING' AND brain_session_id IS NOT NULL",
                "CREATE INDEX waiting_question_companion_idx ON waiting_question(companion_id,state,updated_at)");
        List<String> taskGraphExecution = List.of(
                """
                CREATE TABLE task_graph_execution (
                  execution_id TEXT PRIMARY KEY,
                  controller_id TEXT NOT NULL,
                  brain_session_id TEXT NOT NULL,
                  companion_id TEXT NOT NULL,
                  graph_id TEXT NOT NULL,
                  graph_version TEXT NOT NULL,
                  graph_hash TEXT NOT NULL,
                  graph_json TEXT NOT NULL,
                  state TEXT NOT NULL,
                  current_node_id TEXT,
                  completed_nodes_json TEXT NOT NULL,
                  tool_results_json TEXT NOT NULL,
                  variables_json TEXT NOT NULL,
                  checkpoints_json TEXT NOT NULL,
                  waiting_question_json TEXT,
                  permissions_json TEXT NOT NULL,
                  limits_json TEXT NOT NULL,
                  provenance_json TEXT NOT NULL,
                  revision INTEGER NOT NULL,
                  result_code TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """,
                "CREATE INDEX task_graph_execution_companion_idx ON task_graph_execution(companion_id,state,updated_at)");
        List<String> taskGraphRuntimeState = List.of(
                "ALTER TABLE task_graph_execution ADD COLUMN inputs_json TEXT NOT NULL DEFAULT '{}'",
                "ALTER TABLE task_graph_execution ADD COLUMN evidence_json TEXT NOT NULL DEFAULT '[]'");
        return List.of(
                new Migration(1, "initial runtime schema", statements),
                new Migration(2, "durable command correlation and single active task", taskSafety),
                new Migration(3, "persist task control epochs", epochTracking),
                new Migration(4, "durable agent plans, steps, observations, and typed memory", agentKernel),
                new Migration(5, "persist replan budgets, semantic revisions, and loop fingerprints", replanning),
                new Migration(6, "persist companion conversation and waiting questions", companionInteraction),
                new Migration(7, "record typed memory provenance", memoryProvenance),
                new Migration(8, "persist external brain sessions and tool observations", brainAudit),
                new Migration(9, "bind asynchronous brain tools to durable tasks", asynchronousBrainTools),
                new Migration(10, "persist external brain waiting questions", externalBrainQuestions),
                new Migration(11, "persist typed task graph execution state", taskGraphExecution),
                new Migration(12, "persist task graph inputs and bounded evidence", taskGraphRuntimeState));
    }
}
