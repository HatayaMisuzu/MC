package com.mccompanion.runtime.task;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class TaskRepositoryTest {
    @TempDir Path temporary;
    private TaskEventStore events;
    private TaskRepository tasks;

    @BeforeEach
    void setUp() throws Exception {
        RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("task.db"));
        database.initialize();
        events = new TaskEventStore(database);
        tasks = new TaskRepository(database, events);
    }

    @Test
    void persistsEventsSnapshotsAndRequiresReconciliationAfterRestart() throws Exception {
        TaskRecord created = tasks.create("companion-1", TaskType.FOLLOW, "跟着我", Json.object(),
                "command-1", "START_BEHAVIOR", 7);
        assertEquals(TaskState.CREATED, created.state());
        assertEquals(7, created.controlEpoch());
        assertEquals(1, events.list(created.taskId()).size());
        TaskRecord accepted = tasks.transition(created.taskId(), 0, TaskState.ACCEPTED,
                "CommandAccepted", Json.object());
        assertEquals(1, accepted.revision());
        TaskRecord recovering = tasks.markUnfinishedForReconciliation().getFirst();
        assertEquals(TaskState.RECONCILIATION_REQUIRED, recovering.state());
        assertTrue(recovering.reconciliationRequired());
        assertEquals(3, events.list(created.taskId()).size());
        TaskRecord cancelled = tasks.reconcile(created.taskId(), "wrong-behavior", 0, TaskState.RUNNING);
        assertEquals(TaskState.CANCELLED, cancelled.state());
    }

    @Test
    void databasePreventsTwoActiveTasksForOneCompanion() throws Exception {
        tasks.create("companion-2", TaskType.FOLLOW, "follow", Json.object());
        assertThrows(SQLException.class,
                () -> tasks.create("companion-2", TaskType.TRAVEL, "goto", Json.object()));
    }

    @Test
    void rejectsStaleRevisionInsteadOfOverwritingNewerState() throws Exception {
        TaskRecord task = tasks.create("companion-3", TaskType.RETURN, "come", Json.object());
        tasks.transition(task.taskId(), 0, TaskState.ACCEPTED, "accepted", Json.object());
        assertThrows(TaskRepository.StaleTaskRevisionException.class,
                () -> tasks.transition(task.taskId(), 0, TaskState.RUNNING, "stale", Json.object()));
    }
}

