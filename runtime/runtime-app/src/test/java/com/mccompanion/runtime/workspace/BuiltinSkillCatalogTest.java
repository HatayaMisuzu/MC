package com.mccompanion.runtime.workspace;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.taskgraph.TaskGraphCodec;
import com.mccompanion.runtime.taskgraph.TaskGraphExecutionRepository;
import com.mccompanion.runtime.taskgraph.TaskGraphExecutor;
import com.mccompanion.runtime.taskgraph.TaskGraphRuntime;
import com.mccompanion.runtime.taskgraph.TaskGraphValidator;
import com.mccompanion.runtime.tool.CompositeToolGateway;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinSkillCatalogTest {
    @TempDir Path temporary;

    @Test
    void allCompatibilityWrappersAreReadOnlyValidTaskGraphs() {
        BuiltinSkillCatalog catalog = new BuiltinSkillCatalog();
        assertEquals(List.of("collect_resource", "craft_item", "defend_owner", "mine_vein",
                        "smelt_item", "withdraw_storage"),
                catalog.list().stream().map(BuiltinSkillCatalog.BuiltinSkill::skillId).toList());
        Map<String, ToolDefinition> tools = Map.of(
                "resource.collect", definition("resource.collect", "COLLECT"),
                "resource.mine_vein", definition("resource.mine_vein", "MINE"),
                "item.smelt", definition("item.smelt", "CRAFT"),
                "inventory.withdraw", definition("inventory.withdraw", "INVENTORY"),
                "item.craft", definition("item.craft", "CRAFT"),
                "combat.defend_owner", definition("combat.defend_owner", "COMBAT"));
        TaskGraphValidator validator = new TaskGraphValidator();
        for (BuiltinSkillCatalog.BuiltinSkill skill : catalog.list()) {
            assertFalse(skill.document().isBlank());
            assertEquals(64, skill.sha256().length());
            var graph = TaskGraphCodec.parse(skill.document(), TaskGraphCodec.Format.YAML);
            var result = validator.validateExecutable(graph, tools, TaskGraphExecutor.EXECUTABLE_NODE_TYPES);
            assertTrue(result.valid(), skill.skillId() + ": " + result.issues());
        }
    }

    @Test
    void builtInSkillExecutesThroughSamePersistentRuntimeAndCannotBeOverwritten() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("builtins.db"))) {
            database.initialize();
            AtomicInteger calls = new AtomicInteger();
            ToolGateway combat = new ToolGateway() {
                @Override public List<ToolDefinition> definitions(ToolContext context) {
                    return List.of(definition("combat.defend_owner", "COMBAT"));
                }

                @Override public ToolResult execute(ToolContext context, ToolCall call) {
                    calls.incrementAndGet();
                    return new ToolResult(call.callId(), call.name(), true, "OK",
                            Json.object().put("defended", true), true);
                }
            };
            SkillToolGateway[] skill = new SkillToolGateway[1];
            CompositeToolGateway[] composite = new CompositeToolGateway[1];
            skill[0] = new SkillToolGateway(new AgentWorkspace(temporary.resolve("workspace"), "profile"),
                    new SkillRepository(database), "profile", context -> composite[0].definitions(context));
            composite[0] = new CompositeToolGateway(List.of(skill[0], combat));
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(composite[0],
                    new TaskGraphExecutionRepository(database))) {
                skill[0].attachTaskGraphRuntime(runtime);
                ToolContext context = new ToolContext("controller", "brain", "c1");
                var overwrite = skill[0].execute(context, new ToolCall("overwrite", "skill.save_draft",
                        Json.object().put("skillId", "defend_owner").put("format", "yaml")
                                .put("document", "not allowed")));
                assertFalse(overwrite.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", overwrite.code());

                ToolCall execute = new ToolCall("builtin-execution", "skill.execute",
                        Json.object().put("skillId", "defend_owner"));
                ToolResult accepted = skill[0].execute(context, execute);
                assertTrue(accepted.success(), accepted.observation().toString());
                ToolResult terminal = skill[0].awaitTerminal(context, execute, accepted,
                        Duration.ofSeconds(5), progress -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals(1, calls.get());
                assertEquals("BUILT_IN_SKILL", new TaskGraphExecutionRepository(database)
                        .get("builtin-execution").orElseThrow().provenance().path("source").asText());
            }
        }
    }

    private static ToolDefinition definition(String name, String permission) {
        return new ToolDefinition(name, "1.0", "test", Json.object().put("type", "object")
                .put("additionalProperties", true), "LOW", permission, Duration.ofSeconds(5), false);
    }
}
