package com.mccompanion.runtime.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.protocol.BehaviorEvent;
import com.mccompanion.protocol.CommandAccepted;
import com.mccompanion.protocol.CommandType;
import com.mccompanion.protocol.CompanionStatus;
import com.mccompanion.protocol.ErrorEnvelope;
import com.mccompanion.protocol.ProtocolBehaviorState;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.lease.ControlLease;
import com.mccompanion.runtime.lease.LeaseException;
import com.mccompanion.runtime.lease.LeaseService;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.session.CompanionRecord;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.RuntimeSession;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.task.TaskRecord;
import com.mccompanion.runtime.task.TaskRepository;
import com.mccompanion.runtime.task.TaskState;
import com.mccompanion.runtime.task.TaskType;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Coordinates durable tasks, process-local lease bearers, and asynchronous protocol commands. */
public final class CommandService implements SessionRegistry.Listener {
    public static final Duration LEASE_DURATION = Duration.ofSeconds(45);
    private static final String CONTROLLER_ID = "runtime-main";

    private final SessionRegistry sessions;
    private final CompanionRepository companions;
    private final TaskRepository tasks;
    private final LeaseService leases;
    private final IdempotencyStore idempotency;
    private final ProtocolCommandSender sender;
    private final RuntimeLog log;
    private volatile TaskLifecycleListener taskLifecycleListener = (task, observation) -> { };

    public CommandService(SessionRegistry sessions, CompanionRepository companions, TaskRepository tasks,
                          LeaseService leases, IdempotencyStore idempotency, ProtocolCommandSender sender,
                          RuntimeLog log) {
        this.sessions = sessions;
        this.companions = companions;
        this.tasks = tasks;
        this.leases = leases;
        this.idempotency = idempotency;
        this.sender = sender;
        this.log = log;
    }

    public synchronized CommandReply execute(String commandId, String companionId, Intent intent) {
        if (intent == null) {
            return CommandReply.rejected("INVALID_REQUEST", "Intent is required");
        }
        ObjectNode request = Json.object().put("companionId", companionId).put("type", intent.type().name());
        request.set("arguments", intent.arguments());
        String hash = idempotency.requestHash(request);
        try {
            IdempotencyStore.Claim claim = idempotency.claim(commandId, hash);
            if (claim.state() == IdempotencyStore.ClaimState.CACHED) {
                return CommandReply.fromJson(claim.cachedResponse());
            }
            if (claim.state() == IdempotencyStore.ClaimState.CONFLICT) {
                return CommandReply.rejected("COMMAND_ID_CONFLICT", "commandId was already used for another request");
            }
            if (claim.state() == IdempotencyStore.ClaimState.IN_PROGRESS) {
                return CommandReply.rejected("COMMAND_IN_PROGRESS", "The command is already being processed");
            }
            CommandReply reply;
            try {
                reply = route(commandId, companionId, intent);
            } catch (CommandFailure failure) {
                reply = CommandReply.rejected(failure.code, failure.getMessage());
            } catch (LeaseException failure) {
                reply = CommandReply.rejected(failure.code(), failure.getMessage());
            } catch (SQLException failure) {
                log.error("Command persistence failed: command=" + commandId, failure);
                reply = CommandReply.rejected("PERSISTENCE_ERROR", "Runtime could not persist the command safely");
            } catch (IllegalArgumentException failure) {
                reply = CommandReply.rejected("INVALID_REQUEST", failure.getMessage());
            } catch (RuntimeException failure) {
                log.error("Command dispatch failed: command=" + commandId, failure);
                reply = CommandReply.rejected("RUNTIME_OFFLINE", "Companion session is not available");
            }
            idempotency.complete(commandId, hash, reply.toJson(), reply.accepted());
            return reply;
        } catch (SQLException failure) {
            log.error("Idempotency check failed: command=" + commandId, failure);
            return CommandReply.rejected("PERSISTENCE_ERROR", "Runtime could not persist command idempotency state");
        }
    }

    private CommandReply route(String commandId, String companionId, Intent intent) throws SQLException {
        if (intent.type() == TaskType.STATUS) {
            return status(commandId, companionId);
        }
        if (intent.type() == TaskType.STOP) {
            return switch (intent.arguments().path("action").asText("cancel").toLowerCase(Locale.ROOT)) {
                case "pause" -> pause(commandId, companionId);
                case "resume" -> resume(commandId, companionId);
                case "cancel" -> cancel(commandId, companionId);
                default -> throw new CommandFailure("INVALID_REQUEST", "STOP action must be cancel, pause, or resume");
            };
        }
        return start(commandId, companionId, intent);
    }

    private CommandReply start(String commandId, String companionId, Intent intent) throws SQLException {
        RuntimeSession session = requireSession(companionId);
        Optional<TaskRecord> active = tasks.activeForCompanion(companionId);
        if (active.isPresent()) {
            TaskRecord task = active.get();
            String code = task.state() == TaskState.RECONCILIATION_REQUIRED
                    ? "RECONCILIATION_REQUIRED" : "TASK_ALREADY_ACTIVE";
            throw new CommandFailure(code, "Companion already has active task " + task.taskId()
                    + " in state " + task.state());
        }
        LeaseUse leaseUse = lease(companionId);
        ControlLease lease = leaseUse.lease();
        TaskRecord task = tasks.create(companionId, intent.type(), intent.originalText(), intent.arguments(),
                commandId, CommandType.START_BEHAVIOR.name(), lease.epoch());
        try {
            if (leaseUse.newlyAcquired()) {
                String leaseCommandId = generatedCommandId("lease");
                tasks.linkCommand(leaseCommandId, task.taskId(), CommandType.ACQUIRE_LEASE.name());
                ObjectNode acquireArguments = Json.object()
                        .put("controllerId", CONTROLLER_ID)
                        .put("proposedLeaseId", lease.token())
                        .put("proposedEpoch", lease.epoch())
                        .put("expiresAt", lease.expiresAt().toEpochMilli())
                        .put("mode", lease.mode().name());
                sender.send(session, leaseCommandId, CommandType.ACQUIRE_LEASE, companionId, task.taskId(),
                        null, 0, task.revision(), acquireArguments);
            }
            ObjectNode startArguments = Json.object()
                    .put("behaviorId", task.behaviorId())
                    .put("behaviorType", intent.type().name().toLowerCase(Locale.ROOT))
                    .put("behaviorRevision", task.behaviorRevision());
            startArguments.set("parameters", intent.arguments());
            sender.send(session, commandId, CommandType.START_BEHAVIOR, companionId, task.taskId(), lease.token(),
                    lease.epoch(), task.revision(), startArguments);
            return dispatched(task, lease.epoch(), "Behavior command dispatched; awaiting Mod acceptance");
        } catch (RuntimeException | SQLException dispatchFailure) {
            failCreatedTask(task, "RUNTIME_OFFLINE");
            releaseQuietly(lease);
            throw dispatchFailure;
        }
    }

    private CommandReply pause(String commandId, String companionId) throws SQLException {
        TaskRecord task = requireActiveTask(companionId);
        if (task.state() == TaskState.PAUSED) {
            return dispatched(task, task.controlEpoch(), "Task is already paused");
        }
        if (task.state() == TaskState.CREATED || task.state() == TaskState.RECONCILIATION_REQUIRED) {
            throw new CommandFailure("INVALID_TASK_STATE", "Task cannot be paused while in state " + task.state());
        }
        return sendBehaviorControl(commandId, task, CommandType.PAUSE_BEHAVIOR, "Pause command dispatched");
    }

    private CommandReply resume(String commandId, String companionId) throws SQLException {
        TaskRecord task = requireActiveTask(companionId);
        if (task.state() != TaskState.PAUSED) {
            throw new CommandFailure("INVALID_TASK_STATE", "Only a paused task can be resumed");
        }
        return sendBehaviorControl(commandId, task, CommandType.RESUME_BEHAVIOR, "Resume command dispatched");
    }

    private CommandReply cancel(String commandId, String companionId) throws SQLException {
        TaskRecord task = requireActiveTask(companionId);
        return sendBehaviorControl(commandId, task, CommandType.CANCEL_BEHAVIOR, "Cancel command dispatched");
    }

    private CommandReply sendBehaviorControl(String commandId, TaskRecord task, CommandType type, String message)
            throws SQLException {
        RuntimeSession session = requireSession(task.companionId());
        LeaseUse leaseUse = lease(task.companionId());
        ControlLease lease = leaseUse.lease();
        if (lease.epoch() != task.controlEpoch()) {
            if (task.state() != TaskState.RECONCILIATION_REQUIRED || type != CommandType.CANCEL_BEHAVIOR) {
                throw new CommandFailure("STALE_EPOCH", "Task belongs to an expired control epoch");
            }
        }
        if (leaseUse.newlyAcquired()) {
            sendLeaseAcquire(session, task, lease);
        }
        tasks.linkCommand(commandId, task.taskId(), type.name());
        ObjectNode arguments = Json.object().put("behaviorId", task.behaviorId())
                .put("reason", type == CommandType.CANCEL_BEHAVIOR ? "OWNER_REQUEST" : "OWNER_CONTROL");
        sender.send(session, commandId, type, task.companionId(), task.taskId(), lease.token(), lease.epoch(),
                task.revision(), arguments);
        return dispatched(task, lease.epoch(), message);
    }

    private void sendLeaseAcquire(RuntimeSession session, TaskRecord task, ControlLease lease) throws SQLException {
        String leaseCommandId = generatedCommandId("lease");
        tasks.linkCommand(leaseCommandId, task.taskId(), CommandType.ACQUIRE_LEASE.name());
        ObjectNode arguments = Json.object().put("controllerId", CONTROLLER_ID)
                .put("proposedLeaseId", lease.token()).put("proposedEpoch", lease.epoch())
                .put("expiresAt", lease.expiresAt().toEpochMilli()).put("mode", lease.mode().name());
        sender.send(session, leaseCommandId, CommandType.ACQUIRE_LEASE, task.companionId(), task.taskId(),
                null, 0, task.revision(), arguments);
    }

    private CommandReply status(String commandId, String companionId) throws SQLException {
        CompanionRecord companion = companions.get(companionId)
                .orElseThrow(() -> new CommandFailure("COMPANION_NOT_FOUND", "Companion is not registered"));
        Optional<TaskRecord> task = tasks.activeForCompanion(companionId);
        Optional<RuntimeSession> session = sessions.forCompanion(companionId);
        if (session.isPresent()) {
            sender.send(session.get(), commandId, CommandType.QUERY_STATUS, companionId,
                    task.map(TaskRecord::taskId).orElse(null), null, 0,
                    task.map(TaskRecord::revision).orElse(0L), Json.object());
        }
        ObjectNode data = Json.object().put("online", session.isPresent())
                .put("displayName", companion.displayName());
        data.set("status", companion.status());
        if (task.isPresent()) {
            TaskRecord value = task.get();
            return new CommandReply(true, "STATUS", "Status loaded", value.taskId(), value.state().name(),
                    value.revision(), value.controlEpoch(), data);
        }
        return new CommandReply(true, "STATUS", "No active task", null, "IDLE", 0, 0, data);
    }

    public void onCommandAccepted(CommandAccepted accepted) {
        try {
            TaskRepository.CommandLink link = tasks.commandLink(accepted.commandId()).orElse(null);
            if (link == null || !CommandType.START_BEHAVIOR.name().equals(link.commandType())) {
                return;
            }
            TaskRecord task = link.task();
            if (task.state().terminal() || task.state() != TaskState.CREATED) {
                return;
            }
            if (!task.behaviorId().equals(accepted.behaviorId())) {
                failCreatedTask(task, "BEHAVIOR_ID_MISMATCH");
                return;
            }
            Optional<ControlLease> lease = leases.processLease(task.companionId());
            if (lease.isEmpty() || lease.get().epoch() != task.controlEpoch()) {
                log.warn("Ignored command acceptance for stale task epoch: task=" + task.taskId());
                return;
            }
            tasks.updateBehavior(task.taskId(), task.revision(), accepted.behaviorRevision(), TaskState.ACCEPTED,
                    "CommandAccepted", Json.object().put("commandId", accepted.commandId())
                            .put("duplicate", accepted.duplicate()));
        } catch (SQLException | RuntimeException failure) {
            log.error("Unable to apply command acceptance", failure);
        }
    }

    public void onBehaviorEvent(BehaviorEvent event) {
        try {
            TaskRecord task = event.commandId() == null
                    ? tasks.forBehavior(event.behaviorId()).orElse(null)
                    : tasks.forCommand(event.commandId()).orElseGet(() -> {
                        try {
                            return tasks.forBehavior(event.behaviorId()).orElse(null);
                        } catch (SQLException failure) {
                            throw new PersistenceLookupException(failure);
                        }
                    });
            if (task == null || task.state().terminal()) {
                return;
            }
            if (!task.companionId().equals(event.companionId()) || !task.behaviorId().equals(event.behaviorId())) {
                log.warn("Ignored behavior event whose identity did not match the durable task");
                return;
            }
            long eventEpoch = event.snapshot().path("controlEpoch").asLong(-1);
            if (eventEpoch != task.controlEpoch()) {
                log.warn("Ignored behavior event with stale or missing control epoch for task=" + task.taskId());
                return;
            }
            TaskState next = taskState(event.state());
            ObjectNode payload = Json.object().put("event", event.event().name())
                    .put("tick", event.tick()).put("progress", event.progress());
            if (event.failureCode() != null) payload.put("code", event.failureCode());
            if (event.message() != null) payload.put("message", event.message());
            payload.set("snapshot", event.snapshot());
            TaskRecord updated = tasks.updateBehavior(task.taskId(), task.revision(), event.revision(), next,
                    "Behavior" + title(event.event().name()), payload);
            if (updated.state().terminal()) {
                releaseAfterTerminal(updated);
            }
            taskLifecycleListener.onTaskUpdated(updated, payload);
        } catch (PersistenceLookupException wrapped) {
            log.error("Unable to look up behavior task", wrapped.getCause());
        } catch (SQLException | RuntimeException failure) {
            log.error("Unable to apply behavior lifecycle event", failure);
        }
    }

    public void onProtocolError(ErrorEnvelope error) {
        if (error.commandId() == null) {
            log.warn("Mod reported protocol error: code=" + error.failureCode());
            return;
        }
        try {
            TaskRepository.CommandLink link = tasks.commandLink(error.commandId()).orElse(null);
            if (link == null || link.task().state().terminal()) {
                return;
            }
            TaskRecord task = link.task();
            ObjectNode payload = Json.object().put("code", error.failureCode())
                    .put("message", error.message()).put("retryable", error.retryable())
                    .put("commandType", link.commandType());
            if (CommandType.START_BEHAVIOR.name().equals(link.commandType())
                    || CommandType.ACQUIRE_LEASE.name().equals(link.commandType())) {
                TaskRecord updated = tasks.transition(task.taskId(), task.revision(), TaskState.FAILED,
                        "CommandRejected", payload);
                releaseAfterTerminal(updated);
                taskLifecycleListener.onTaskUpdated(updated, payload);
            } else if (task.state() != TaskState.RECONCILIATION_REQUIRED) {
                tasks.transition(task.taskId(), task.revision(), task.state(), "CommandRejected", payload);
            }
        } catch (SQLException | RuntimeException failure) {
            log.error("Unable to apply protocol error for command=" + error.commandId(), failure);
        }
    }

    @Override
    public void onDisconnected(RuntimeSession session, String reason) {
        session.companionIds().forEach(id -> {
            try {
                leases.revoke(id);
            } catch (SQLException failure) {
                log.error("Unable to revoke disconnected lease for companion=" + id, failure);
            }
            markCompanionForReconciliation(id, reason);
        });
    }

    @Override
    public void onCompanionUpdated(RuntimeSession session, CompanionStatus status, JsonNode statusJson) {
        try {
            Optional<TaskRecord> active = tasks.activeForCompanion(status.companionId());
            if (active.isEmpty() || active.get().state() != TaskState.RECONCILIATION_REQUIRED) {
                return;
            }
            TaskRecord task = active.get();
            boolean epochMatches = status.controlEpoch() == task.controlEpoch();
            tasks.reconcile(task.taskId(), epochMatches ? status.behaviorId() : null,
                    status.behaviorRevision(), status.behaviorState() == null ? null : taskState(status.behaviorState()));
        } catch (SQLException | RuntimeException failure) {
            log.error("Task reconciliation failed for companion=" + status.companionId(), failure);
        }
    }

    public void renewActiveLeases() {
        for (ControlLease lease : leases.processLeases()) {
            try {
                Optional<TaskRecord> task = tasks.activeForCompanion(lease.companionId());
                Optional<RuntimeSession> session = sessions.forCompanion(lease.companionId());
                if (task.isEmpty() || session.isEmpty() || task.get().controlEpoch() != lease.epoch()) {
                    continue;
                }
                ControlLease renewed = leases.renew(lease, LEASE_DURATION);
                sender.send(session.get(), generatedCommandId("renew"), CommandType.RENEW_LEASE,
                        renewed.companionId(), task.get().taskId(), renewed.token(), renewed.epoch(),
                        task.get().revision(), Json.object().put("expiresAt", renewed.expiresAt().toEpochMilli()));
            } catch (SQLException | RuntimeException failure) {
                log.error("Control lease renewal failed for companion=" + lease.companionId(), failure);
            }
        }
    }

    public void expireLeases() {
        try {
            for (LeaseService.ExpiredLease expired : leases.expireDue()) {
                markCompanionForReconciliation(expired.companionId(), "LEASE_EXPIRED");
            }
        } catch (SQLException failure) {
            log.error("Lease expiry sweep failed", failure);
        }
    }

    public void releaseAllLeases() {
        for (ControlLease lease : leases.processLeases()) {
            try {
                Optional<TaskRecord> task = tasks.activeForCompanion(lease.companionId());
                sessions.forCompanion(lease.companionId()).ifPresent(session -> {
                    try {
                        sender.send(session, generatedCommandId("release"), CommandType.RELEASE_LEASE,
                                lease.companionId(), task.map(TaskRecord::taskId).orElse(null), lease.token(),
                                lease.epoch(), task.map(TaskRecord::revision).orElse(0L),
                                Json.object().put("reason", "RUNTIME_SHUTDOWN"));
                    } catch (RuntimeException failure) {
                        log.warn("Unable to notify Mod of lease release for companion=" + lease.companionId());
                    }
                });
                leases.release(lease);
            } catch (SQLException | RuntimeException failure) {
                log.error("Unable to release lease during shutdown for companion=" + lease.companionId(), failure);
            }
        }
    }

    private void releaseAfterTerminal(TaskRecord task) {
        leases.processLease(task.companionId()).ifPresent(lease -> {
            if (lease.epoch() != task.controlEpoch()) return;
            sessions.forCompanion(task.companionId()).ifPresent(session -> {
                try {
                    sender.send(session, generatedCommandId("release"), CommandType.RELEASE_LEASE,
                            task.companionId(), task.taskId(), lease.token(), lease.epoch(), task.revision(),
                            Json.object().put("reason", "TASK_TERMINAL"));
                } catch (RuntimeException failure) {
                    log.warn("Unable to notify Mod of terminal lease release for task=" + task.taskId());
                }
            });
            releaseQuietly(lease);
        });
    }

    private void markCompanionForReconciliation(String companionId, String reason) {
        try {
            Optional<TaskRecord> active = tasks.activeForCompanion(companionId);
            if (active.isPresent() && active.get().state() != TaskState.RECONCILIATION_REQUIRED) {
                TaskRecord task = active.get();
                tasks.transition(task.taskId(), task.revision(), TaskState.RECONCILIATION_REQUIRED,
                        "ReconciliationRequired", Json.object().put("reason", reason));
            }
        } catch (SQLException | RuntimeException failure) {
            log.error("Unable to mark task for reconciliation for companion=" + companionId, failure);
        }
    }

    private RuntimeSession requireSession(String companionId) throws SQLException {
        if (companions.get(companionId).isEmpty()) {
            throw new CommandFailure("COMPANION_NOT_FOUND", "Companion is not registered");
        }
        return sessions.forCompanion(companionId)
                .orElseThrow(() -> new CommandFailure("RUNTIME_OFFLINE", "Companion Mod session is offline"));
    }

    private TaskRecord requireActiveTask(String companionId) throws SQLException {
        return tasks.activeForCompanion(companionId)
                .orElseThrow(() -> new CommandFailure("TASK_NOT_FOUND", "No active task for companion"));
    }

    private LeaseUse lease(String companionId) throws SQLException {
        Optional<ControlLease> current = leases.processLease(companionId);
        if (current.isPresent()) {
            return new LeaseUse(current.get(), false);
        }
        return new LeaseUse(leases.acquire(companionId, CONTROLLER_ID, LEASE_DURATION,
                ControlLease.ControlMode.EXTERNAL_RUNTIME), true);
    }

    private void failCreatedTask(TaskRecord task, String code) {
        try {
            TaskRecord latest = tasks.get(task.taskId()).orElse(task);
            if (!latest.state().terminal() && latest.state() == TaskState.CREATED) {
                tasks.transition(latest.taskId(), latest.revision(), TaskState.FAILED, "BehaviorFailed",
                        Json.object().put("code", code));
            }
        } catch (SQLException | RuntimeException failure) {
            log.error("Unable to mark failed task=" + task.taskId(), failure);
        }
    }

    private void releaseQuietly(ControlLease lease) {
        try {
            leases.release(lease);
        } catch (SQLException | RuntimeException failure) {
            try {
                leases.revoke(lease.companionId());
            } catch (SQLException revokeFailure) {
                failure.addSuppressed(revokeFailure);
            }
            log.error("Unable to release control lease for companion=" + lease.companionId(), failure);
        }
    }

    private static TaskState taskState(ProtocolBehaviorState state) {
        return switch (state) {
            case CREATED, STARTING -> TaskState.ACCEPTED;
            case RUNNING -> TaskState.RUNNING;
            case WAITING -> TaskState.WAITING;
            case PAUSED -> TaskState.PAUSED;
            case BLOCKED -> TaskState.BLOCKED;
            case COMPLETED -> TaskState.COMPLETED;
            case FAILED -> TaskState.FAILED;
            case CANCELLED -> TaskState.CANCELLED;
        };
    }

    private static String title(String enumName) {
        String lower = enumName.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String generatedCommandId(String prefix) {
        return prefix + '-' + UUID.randomUUID();
    }

    public Optional<TaskRecord> task(String taskId) throws SQLException {
        return tasks.get(taskId);
    }

    public Optional<TaskRecord> activeTaskFor(String companionId) throws SQLException {
        return tasks.activeForCompanion(companionId);
    }

    public void setTaskLifecycleListener(TaskLifecycleListener listener) {
        taskLifecycleListener = listener == null ? (task, observation) -> { } : listener;
    }

    @FunctionalInterface
    public interface TaskLifecycleListener {
        void onTaskUpdated(TaskRecord task, JsonNode observation);
    }

    public List<com.mccompanion.runtime.task.TaskEvent> taskEvents(String taskId) throws SQLException {
        return tasks.events(taskId);
    }

    public Optional<ControlLease> leaseFor(String companionId) {
        return leases.processLease(companionId);
    }

    private CommandReply dispatched(TaskRecord task, long epoch, String message) {
        ObjectNode data = Json.object().put("behaviorId", task.behaviorId());
        leases.processLease(task.companionId()).ifPresent(lease -> data.put("leaseId", lease.token()));
        return new CommandReply(true, "COMMAND_DISPATCHED", message, task.taskId(), task.state().name(),
                task.revision(), epoch, data);
    }

    private record LeaseUse(ControlLease lease, boolean newlyAcquired) { }

    private static final class CommandFailure extends RuntimeException {
        private final String code;
        private CommandFailure(String code, String message) { super(message); this.code = code; }
    }

    private static final class PersistenceLookupException extends RuntimeException {
        private PersistenceLookupException(SQLException cause) { super(cause); }
        @Override public synchronized SQLException getCause() { return (SQLException) super.getCause(); }
    }
}
