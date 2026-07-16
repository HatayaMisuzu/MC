package com.mccompanion.runtime.tool;

import com.mccompanion.runtime.capability.CapabilityRegistry;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.Handshake;
import com.mccompanion.runtime.task.TaskEventStore;
import com.mccompanion.runtime.task.TaskRepository;
import com.mccompanion.runtime.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObservationToolGatewayTest {
    @TempDir Path temporary;

    @Test
    void exposesBoundedVerifiedObservationCapabilityAndTaskPrimitives() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("observations.db"))) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            var status = Json.object().put("dimension", "examplemod:moon")
                    .put("bodyState", "spawned").put("runtimeConnected", true)
                    .put("observedAt", "2026-07-16T10:00:00Z")
                    .put("behaviorState", "running").put("behaviorRevision", 4).put("controlEpoch", 8);
            status.set("position", Json.object().put("x", 2).put("y", 70).put("z", -3));
            status.set("vitals", Json.object().put("health", 5).put("maxHealth", 20)
                    .put("food", 12).put("air", 30).put("onFire", false).put("inLava", true));
            status.set("inventory", Json.object().put("freeSlots", 31)
                    .set("counts", Json.object().put("examplemod:moon_dust", 7)));
            status.set("observedContainers", Json.MAPPER.createArrayNode().add(
                    Json.object().put("type", "examplemod:crate").put("dimension", "examplemod:moon")
                            .put("x", 3).put("y", 70).put("z", -3).put("verified", true)));
            companions.upsert("c1", "session", "world", "owner", "Misuzu", status);
            TaskRepository tasks = new TaskRepository(database, new TaskEventStore(database));
            Handshake handshake = new Handshake("mc-companion/1", "test", "1.21.1", "fabric", "world",
                    Json.object().put("NavigateTo", true).put("CollectResource", true));
            ObservationToolGateway gateway = new ObservationToolGateway(companions, tasks,
                    CapabilityRegistry.standard(), ignored -> handshake);
            ToolContext context = new ToolContext("controller", "brain", "c1");

            assertEquals(List.of("world.query", "inventory.inspect", "safety.inspect", "task.inspect",
                            "capability.list", "capability.describe"),
                    gateway.definitions(context).stream().map(ToolDefinition::name).toList());
            var queried = gateway.execute(context, new ToolCall("q", "world.query",
                    Json.object().put("select", "position")));
            assertTrue(queried.success());
            assertEquals("CONNECTED_BODY_OBSERVATION", queried.observation().path("source").asText());
            assertEquals(2, queried.observation().path("value").path("x").asInt());
            assertEquals("examplemod:moon", queried.observation().path("dimension").asText());

            var inventory = gateway.execute(context, new ToolCall("i", "inventory.inspect", Json.object()));
            assertEquals(7, inventory.observation().path("inventory").path("counts")
                    .path("examplemod:moon_dust").asInt());
            var safety = gateway.execute(context, new ToolCall("s", "safety.inspect", Json.object()));
            assertFalse(safety.observation().path("safe").asBoolean());
            assertEquals(List.of("IN_LAVA", "LOW_AIR", "LOW_HEALTH"),
                    java.util.stream.StreamSupport.stream(
                                    safety.observation().path("hazards").spliterator(), false)
                            .map(value -> value.asText()).toList());
            assertFalse(safety.observation().path("threatScanIncluded").asBoolean());

            var capabilityList = gateway.execute(context,
                    new ToolCall("cl", "capability.list", Json.object()));
            assertTrue(capabilityList.success());
            assertTrue(java.util.stream.StreamSupport.stream(
                            capabilityList.observation().path("capabilities").spliterator(), false)
                    .anyMatch(value -> value.path("name").asText().equals("NavigateTo")
                            && value.path("state").asText().equals("AVAILABLE_NOW")));
            var described = gateway.execute(context, new ToolCall("cd", "capability.describe",
                    Json.object().put("name", "CollectResource")));
            assertTrue(described.success());
            assertEquals("MEDIUM", described.observation().path("definition").path("risk").asText());
            assertEquals("AVAILABLE_NOW", described.observation().path("status").path("state").asText());

            assertEquals("IDLE", gateway.execute(context,
                    new ToolCall("t0", "task.inspect", Json.object())).observation().path("state").asText());
            var task = tasks.create("c1", TaskType.TRAVEL, "move",
                    Json.object().set("target", Json.object().put("x", 1).put("y", 70).put("z", 1)));
            var inspected = gateway.execute(context, new ToolCall("t1", "task.inspect", Json.object()));
            assertEquals(task.taskId(), inspected.observation().path("task").path("taskId").asText());
            assertFalse(inspected.observation().path("events").isEmpty());
        }
    }

    @Test
    void rejectsUnknownSelectorsFieldsCapabilitiesAndMissingObservation() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("reject.db"))) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            companions.upsert("c1", null, "world", "owner", "Misuzu", Json.object());
            ObservationToolGateway gateway = new ObservationToolGateway(companions,
                    new TaskRepository(database, new TaskEventStore(database)), CapabilityRegistry.standard(),
                    ignored -> null);
            ToolContext context = new ToolContext("controller", "brain", "c1");
            assertEquals("INVALID_TOOL_ARGUMENTS", gateway.execute(context,
                    new ToolCall("bad-query", "world.query", Json.object().put("select", "shell"))).code());
            assertEquals("INVALID_TOOL_ARGUMENTS", gateway.execute(context,
                    new ToolCall("bad-field", "inventory.inspect", Json.object().put("path", "C:/"))).code());
            assertEquals("INVALID_TOOL_ARGUMENTS", gateway.execute(context,
                    new ToolCall("bad-capability", "capability.describe",
                            Json.object().put("name", "UnknownCapability"))).code());
            assertEquals("OBSERVATION_UNAVAILABLE", gateway.execute(context,
                    new ToolCall("missing", "inventory.inspect", Json.object())).code());
        }
    }
}
