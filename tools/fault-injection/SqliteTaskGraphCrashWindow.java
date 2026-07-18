import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

/**
 * Test-process coordinator for the live Runtime/Fabric crash-window E2E.
 *
 * <p>This is deliberately outside production source sets. It blocks deletion of one companion's
 * control lease after a durable Tool task reaches a terminal state, leaving the production
 * TaskGraphRuntime waiting between the durable Tool result and its Graph result snapshot.</p>
 */
public final class SqliteTaskGraphCrashWindow {
    private static final String TRIGGER = "mcac_e2e_hold_terminal_lease";

    private SqliteTaskGraphCrashWindow() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) {
            throw new IllegalArgumentException("usage: <arm|await|verify|disarm> <database> [arguments]");
        }
        Class.forName("org.sqlite.JDBC");
        String mode = arguments[0];
        Path database = Path.of(arguments[1]).toAbsolutePath().normalize();
        String url = "jdbc:sqlite:" + database;
        switch (mode) {
            case "arm" -> {
                require(arguments, 3);
                arm(url, arguments[2]);
            }
            case "await" -> {
                require(arguments, 5);
                awaitBoundary(url, arguments[2], arguments[3],
                        Duration.ofSeconds(Long.parseLong(arguments[4])));
            }
            case "verify" -> {
                require(arguments, 4);
                verify(url, arguments[2], arguments[3]);
            }
            case "disarm" -> disarm(url);
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    private static void arm(String url, String companionId) throws Exception {
        String exactCompanion = companionId.replace("'", "''");
        try (Connection connection = open(url); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TRIGGER IF EXISTS " + TRIGGER);
            statement.executeUpdate("""
                    CREATE TRIGGER %s BEFORE DELETE ON control_lease
                    WHEN OLD.companion_id = '%s'
                    BEGIN
                      SELECT RAISE(ABORT, 'injected terminal lease hold');
                    END
                    """.formatted(TRIGGER, exactCompanion));
        }
    }

    private static void awaitBoundary(String url, String commandId, String executionId,
                                      Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = open(url)) {
                String taskState = taskState(connection, commandId);
                GraphBoundary graph = graphBoundary(connection, executionId);
                if ("COMPLETED".equals(taskState)
                        && "RUNNING".equals(graph.state())
                        && graph.toolResultsEmpty()) {
                    System.out.println("BOUNDARY_READY task=COMPLETED graph=RUNNING toolResults=EMPTY");
                    return;
                }
                if (graph.toolResultRecorded()) {
                    throw new IllegalStateException(
                            "Graph result was persisted before the injected crash boundary");
                }
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("timed out waiting for terminal Tool / absent Graph result boundary");
    }

    private static void disarm(String url) throws Exception {
        try (Connection connection = open(url); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TRIGGER IF EXISTS " + TRIGGER);
        }
    }

    private static void verify(String url, String commandId, String executionId) throws Exception {
        try (Connection connection = open(url);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT t.state,
                       (SELECT COUNT(*) FROM task_command exact WHERE exact.command_id=?),
                       (SELECT COUNT(*) FROM behavior_run behavior WHERE behavior.task_id=t.task_id)
                     FROM task t JOIN task_command c ON c.task_id=t.task_id
                     WHERE c.command_id=?
                     """)) {
            statement.setString(1, commandId);
            statement.setString(2, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("durable Tool task is missing");
                String taskState = rows.getString(1);
                int commandLinks = rows.getInt(2);
                int behaviorRuns = rows.getInt(3);
                GraphBoundary graph = graphBoundary(connection, executionId);
                if (!"COMPLETED".equals(taskState)
                        || commandLinks != 1
                        || behaviorRuns != 1
                        || !"SUCCEEDED".equals(graph.state())
                        || !graph.toolResultRecorded()) {
                    throw new IllegalStateException("recovery verification failed: task=" + taskState
                            + " commandLinks=" + commandLinks + " behaviorRuns=" + behaviorRuns
                            + " graph=" + graph.state() + " graphResult=" + graph.toolResultRecorded());
                }
                System.out.printf(
                        "VERIFY_OK task=COMPLETED commandLinks=1 behaviorRuns=1 graph=SUCCEEDED%n");
            }
        }
    }

    private static String taskState(Connection connection, String commandId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.state FROM task t
                JOIN task_command c ON c.task_id=t.task_id
                WHERE c.command_id=?
                """)) {
            statement.setString(1, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : "";
            }
        }
    }

    private static GraphBoundary graphBoundary(Connection connection, String executionId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT state, tool_results_json FROM task_graph_execution WHERE execution_id=?
                """)) {
            statement.setString(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return new GraphBoundary("", true, false);
                String results = rows.getString(2);
                boolean empty = results == null || results.isBlank() || "{}".equals(results.strip());
                return new GraphBoundary(rows.getString(1), empty, !empty);
            }
        }
    }

    private static Connection open(String url) throws Exception {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private static void require(String[] arguments, int count) {
        if (arguments.length != count) {
            throw new IllegalArgumentException("expected " + count + " arguments");
        }
    }

    private record GraphBoundary(String state, boolean toolResultsEmpty, boolean toolResultRecorded) { }
}
