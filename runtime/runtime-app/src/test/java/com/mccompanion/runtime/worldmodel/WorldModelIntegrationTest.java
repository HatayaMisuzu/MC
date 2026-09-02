package com.mccompanion.runtime.worldmodel;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.brain.BoundedBrainContextAssembler;
import com.mccompanion.runtime.capability.CapabilityRegistry;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.event.RuntimeEventRepository;
import com.mccompanion.runtime.event.RuntimeEventService;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.memory.MemoryKind;
import com.mccompanion.runtime.memory.MemoryRepository;
import com.mccompanion.runtime.memory.MemoryToolGateway;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.task.TaskEventStore;
import com.mccompanion.runtime.task.TaskRepository;
import com.mccompanion.runtime.taskgraph.TaskGraphExecutionRepository;
import com.mccompanion.runtime.taskgraph.TaskGraphRuntime;
import com.mccompanion.runtime.tool.ObservationToolGateway;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldModelIntegrationTest {
    @TempDir Path temporary;

    @Test void persistsInvalidationAndFeedsActualGraphAndBrainProjection() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("world.db"))) {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            Instant now = Instant.now();
            var status = WorldModelTest.status(now);
            status.putObject(WorldModel.STORAGE_FIELD).put("version", 1).put("injected", true);
            companions.upsert("c1", "session", "world", "owner", "Body", status);
            assertFalse(companions.worldModel("c1").saved().has("injected"));
            var tasks = new TaskRepository(database, new TaskEventStore(database));
            var gateway = new ObservationToolGateway(companions, tasks, CapabilityRegistry.standard(), ignored -> null);
            ToolContext context = new ToolContext("controller", "brain", "c1");
            try (RuntimeEventService events = new RuntimeEventService(new RuntimeEventRepository(database));
                 TaskGraphRuntime graph = new TaskGraphRuntime(gateway, new TaskGraphExecutionRepository(database))) {
                events.attachWorldModel(companions);
                graph.setLifecycleListener(events);
                ToolCall call = new ToolCall("graph-call", "task_graph.execute", Json.object());
                graph.start(context, call, Json.parse("""
                    {"version":"mcac-task-graph/1","id":"world-read","permissions":["READ_WORLD"],
                     "root":{"id":"read-model","type":"call_tool","tool":"world.query","arguments":{"select":"model"}}}
                    """), Json.object(), Json.object());
                var result = graph.await(context, call, Duration.ofSeconds(3), ignored -> {});
                assertTrue(result.success(), result.toString());
                assertEquals("SUCCEEDED", companions.worldModel("c1").current("taskGraph", Instant.now())
                        .path("value").path("state").asText());
                events.admit(WorldModelTest.event("TARGET_CONTAINER_CHANGED", WorldModelTest.DIM + ":2,64,0", Instant.now()),
                        com.mccompanion.runtime.event.RuntimeEvent.AdmissionPolicy.immediate());
            }
            CompanionRepository reloaded = new CompanionRepository(database);
            var memories = new MemoryRepository(database);
            memories.remember("c1", MemoryKind.WORLD, "home", WorldModelTest.position(20), true, 1, null, "USER");
            var world = memories.enrichVerifiedWorld("c1", reloaded.get("c1").orElseThrow().status());
            assertEquals("WORLD_MODEL", world.path("source").asText());
            assertFalse(world.has(WorldModel.STORAGE_FIELD));
            var brain = BoundedBrainContextAssembler.assemble(new AgentContext("c1", world, List.of(), Json.object(),
                    memories.verifiedLandmarkKeys("c1"), List.of(), Json.object(), 5));
            assertEquals(world, brain.context().path("verifiedWorld"));
            assertEquals(List.of("home"), memories.verifiedLandmarkKeys("c1"));
            var containers = new MemoryToolGateway(memories, null, reloaded).execute(context,
                    new ToolCall("containers", "world.locate_known_container", Json.object()));
            assertTrue(containers.success());
            assertFalse(containers.observation().path("containers").get(0).path("verified").asBoolean());
            reloaded.invalidateWorldModel("c1", "DISCONNECTED");
            var stale = gateway.execute(context, new ToolCall("inventory", "inventory.inspect", Json.object()));
            assertFalse(stale.success());
            assertEquals("OBSERVATION_STALE", stale.code());
            assertFalse(stale.observation().path("verified").asBoolean());
        }
    }
}
