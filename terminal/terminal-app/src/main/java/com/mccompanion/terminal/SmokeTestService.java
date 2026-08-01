package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mccompanion.protocol.security.OwnerOnlyFile;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.runtime.RuntimeProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class SmokeTestService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration FRESHNESS = Duration.ofDays(7);
    record Result(boolean success, boolean manualRequired, String summary) { }
    record StoredResult(String state, boolean success, boolean manualRequired, String summary,
                        Instant completedAt) { }

    Result run(MinecraftInstance instance, RuntimeProfile profile) {
        Result result = execute(instance, profile);
        try { record(profile, result, Clock.systemUTC()); }
        catch (IOException ignored) { }
        return result;
    }

    private Result execute(MinecraftInstance instance, RuntimeProfile profile) {
        if (!FullBridgeSupport.supports(instance)) {
            return new Result(true, true, "LOCAL_ONLY: verify Mod load log and /companion status manually");
        }
        ConnectionService connections = new ConnectionService();
        var state = connections.status(profile);
        if (!state.connected()) return new Result(false, false, "No authenticated Full Bridge Mod handshake");
        String companionId = connections.firstCompanionId(profile).orElse(null);
        if (companionId == null) {
            return new Result(false, true,
                    "Handshake OK; run /companion create TerminalTest in Minecraft, then rerun");
        }

        RuntimeControlClient client = new RuntimeControlClient();
        Duration requestTimeout = Duration.ofSeconds(5);
        Duration eventTimeout = Duration.ofSeconds(30);
        String taskId = null;
        try {
            JsonNode status = client.execute(profile, commandId(), companionId, "STATUS", object(), requestTimeout);
            requireAccepted(status, "QUERY_STATUS");
            JsonNode rejected = client.execute(profile, commandId(), "missing-smoke-companion", "STATUS",
                    object(), requestTimeout);
            if (rejected.path("accepted").asBoolean()) throw new IOException("Invalid companion was accepted");

            String followCommand = commandId();
            JsonNode follow = client.execute(profile, followCommand, companionId, "FOLLOW", object(), requestTimeout);
            requireAccepted(follow, "FOLLOW");
            taskId = required(follow, "taskId");
            String behaviorId = required(follow.path("data"), "behaviorId");
            required(follow.path("data"), "leaseId");
            long epoch = follow.path("controlEpoch").asLong(0);
            if (epoch <= 0) throw new IOException("FOLLOW did not return a control epoch");

            JsonNode duplicate = client.execute(profile, followCommand, companionId, "FOLLOW", object(), requestTimeout);
            if (!taskId.equals(duplicate.path("taskId").asText())) {
                throw new IOException("Duplicate command did not return the cached task");
            }

            JsonNode running = waitFor(profile, client, taskId, "RUNNING", "BehaviorStarted", eventTimeout);
            verifyIdentity(running, behaviorId, epoch);
            requireAccepted(client.execute(profile, commandId(), companionId, "STOP",
                    object().put("action", "pause"), requestTimeout), "PAUSE");
            waitFor(profile, client, taskId, "PAUSED", "BehaviorPaused", eventTimeout);
            requireAccepted(client.execute(profile, commandId(), companionId, "STOP",
                    object().put("action", "resume"), requestTimeout), "RESUME");
            waitFor(profile, client, taskId, "RUNNING", "BehaviorResumed", eventTimeout);
            requireAccepted(client.execute(profile, commandId(), companionId, "STOP",
                    object().put("action", "cancel"), requestTimeout), "CANCEL");
            JsonNode cancelled = waitFor(profile, client, taskId, "CANCELLED", "BehaviorCancelled", eventTimeout);
            verifyEventOrder(cancelled);
            if (cancelled.path("leaseActive").asBoolean()) throw new IOException("Control lease remained active");
            return new Result(true, false,
                    "Behavior chain passed: status, follow, pause, resume, cancel, event order, idempotency and lease release");
        } catch (IOException failure) {
            if (taskId != null) {
                try { client.execute(profile, commandId(), companionId, "STOP",
                        object().put("action", "cancel"), requestTimeout); }
                catch (IOException ignored) { }
            }
            return new Result(false, false, "Behavior smoke failed safely: " + failure.getMessage());
        }
    }

    StoredResult latest(RuntimeProfile profile) throws IOException {
        Path file = resultFile(profile);
        if (!Files.isRegularFile(file)) {
            return new StoredResult("WAITING", false, false, "No smoke result recorded", Instant.EPOCH);
        }
        JsonNode value = JSON.readTree(file.toFile());
        Instant completedAt = Instant.parse(value.path("completedAt").asText());
        boolean success = value.path("success").asBoolean(false);
        boolean manualRequired = value.path("manualRequired").asBoolean(false);
        String state = value.path("state").asText(success ? "SUCCEEDED" : "FAILED");
        if (completedAt.plus(FRESHNESS).isBefore(Instant.now())) state = "STALE";
        return new StoredResult(
                state, success, manualRequired, value.path("summary").asText(""), completedAt);
    }

    void record(RuntimeProfile profile, Result result, Clock clock) throws IOException {
        Path target = resultFile(profile);
        Files.createDirectories(target.getParent());
        Path temporary = OwnerOnlyFile.createTempFile(target.getParent(), ".smoke-result-", ".part");
        try {
            String state = result.manualRequired() ? "MANUAL_REQUIRED"
                    : result.success() ? "SUCCEEDED" : "FAILED";
            ObjectNode value = JSON.createObjectNode()
                    .put("schemaVersion", 1)
                    .put("state", state)
                    .put("success", result.success())
                    .put("manualRequired", result.manualRequired())
                    .put("summary", new PrivacyFilter().filter(
                            result.summary(), PrivacyFilter.Policy.UI_DEFAULT))
                    .put("completedAt", clock.instant().toString());
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            OwnerOnlyFile.secure(temporary);
            try {
                Files.move(temporary, target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            OwnerOnlyFile.secure(target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path resultFile(RuntimeProfile profile) {
        return profile.profileDirectory().resolve("smoke-result.json");
    }

    private static JsonNode waitFor(RuntimeProfile profile, RuntimeControlClient client, String taskId,
                                    String state, String event, Duration timeout) throws IOException {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode value;
        do {
            value = client.task(profile, taskId, Duration.ofSeconds(3));
            if (state.equals(value.path("task").path("state").asText()) && hasEvent(value, event)) return value;
            try { Thread.sleep(100); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Smoke wait interrupted", interrupted);
            }
        } while (Instant.now().isBefore(deadline));
        throw new IOException("Timed out waiting for " + event + " / " + state);
    }

    private static boolean hasEvent(JsonNode task, String event) {
        for (JsonNode value : task.path("events")) if (event.equals(value.path("eventType").asText())) return true;
        return false;
    }

    private static void verifyIdentity(JsonNode response, String behaviorId, long epoch) throws IOException {
        JsonNode task = response.path("task");
        if (!behaviorId.equals(task.path("behaviorId").asText())) throw new IOException("Behavior identity changed");
        if (task.path("behaviorRevision").asLong(-1) < 0) throw new IOException("Behavior revision is missing");
        if (task.path("controlEpoch").asLong() != epoch) throw new IOException("Control epoch changed");
    }

    private static void verifyEventOrder(JsonNode response) throws IOException {
        List<String> lifecycle = List.of("BehaviorStarted", "BehaviorPaused", "BehaviorResumed", "BehaviorCancelled");
        List<String> observed = new ArrayList<>();
        long previousRevision = -1;
        for (JsonNode event : response.path("events")) {
            long revision = event.path("revision").asLong(-1);
            if (revision < previousRevision) throw new IOException("Task event revisions are out of order");
            previousRevision = revision;
            String type = event.path("eventType").asText();
            if (lifecycle.contains(type)) observed.add(type);
        }
        if (!observed.equals(lifecycle)) throw new IOException("Unexpected lifecycle event order: " + observed);
    }

    private static void requireAccepted(JsonNode response, String operation) throws IOException {
        if (!response.path("accepted").asBoolean()) {
            throw new IOException(operation + " rejected: " + response.path("code").asText("UNKNOWN"));
        }
    }

    private static String required(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IOException(field + " is missing");
        return value;
    }

    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    private static String commandId() { return "smoke-" + UUID.randomUUID(); }
}
