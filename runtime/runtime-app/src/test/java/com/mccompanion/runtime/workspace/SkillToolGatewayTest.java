package com.mccompanion.runtime.workspace;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.taskgraph.TaskGraphExecutionRepository;
import com.mccompanion.runtime.taskgraph.TaskGraphRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolGatewayTest {
    @TempDir Path temporary;

    @Test
    void savesReadsListsValidatesAndRequestsUserPromotion() throws Exception {
        AgentWorkspace workspace = new AgentWorkspace(temporary.resolve("workspace"), "profile");
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("skills.db"))) {
            database.initialize();
            SkillRepository repository = new SkillRepository(database);
            SkillToolGateway[] holder = new SkillToolGateway[1];
            holder[0] = new SkillToolGateway(workspace, repository, "profile",
                    context -> holder[0].definitions(context));
            SkillToolGateway gateway = holder[0];
            ToolContext context = new ToolContext("controller", "brain-session", "companion-a");
            assertEquals(List.of("skill.list", "skill.read", "skill.save_draft", "skill.validate",
                            "skill.request_promotion", "skill.disable", "skill.rollback", "skill.execute"),
                    gateway.definitions(context).stream().map(value -> value.name()).toList());

            String graph = """
                    version: mcac-task-graph/1
                    id: reusable_wait
                    permissions: []
                    provenance:
                      provider: replay-test
                    root:
                      id: done
                      type: return
                      value: complete
                    """;
            var saved = gateway.execute(context, new ToolCall("save", "skill.save_draft", Json.object()
                    .put("skillId", "reusable_wait").put("format", "yaml").put("document", graph)));
            assertTrue(saved.success(), saved.observation().toString());
            assertEquals("SKILL_DRAFT_QUARANTINED", saved.code());
            assertEquals("QUARANTINED", saved.observation().path("trust").asText());
            assertEquals(1, saved.observation().path("version").asInt());

            var read = gateway.execute(context, new ToolCall("read", "skill.read", Json.object()
                    .put("skillId", "reusable_wait").put("format", "yaml")));
            assertTrue(read.success());
            assertEquals(graph, read.observation().path("content").asText());
            assertFalse(read.observation().toString().contains(temporary.toString()));

            var validated = gateway.execute(context, new ToolCall("validate", "skill.validate", Json.object()
                    .put("skillId", "reusable_wait").put("format", "yaml")));
            assertTrue(validated.success(), validated.observation().toString());
            assertEquals("GENERATED_VALIDATED", validated.observation().path("trust").asText());
            assertTrue(validated.observation().path("valid").asBoolean());

            var requested = gateway.execute(context, new ToolCall("promote", "skill.request_promotion",
                    Json.object().put("skillId", "reusable_wait").put("format", "yaml")));
            assertTrue(requested.success(), requested.observation().toString());
            assertEquals("SKILL_PROMOTION_PENDING_USER_APPROVAL", requested.code());
            assertEquals("PENDING_REVIEW", requested.observation().path("status").asText());
            assertTrue(requested.observation().path("requiresUserApproval").asBoolean());
            assertFalse(requested.observation().has("document"));
            assertEquals("brain-session", requested.observation().path("brainSessionId").asText());
            repository.approve(requested.observation().path("requestId").asText(), "user:owner");

            String secondGraph = graph.replace("value: complete", "value: complete-v2");
            assertTrue(gateway.execute(context, new ToolCall("save-v2", "skill.save_draft", Json.object()
                    .put("skillId", "reusable_wait").put("format", "yaml")
                    .put("document", secondGraph))).success());
            var secondRequest = gateway.execute(context, new ToolCall("promote-v2", "skill.request_promotion",
                    Json.object().put("skillId", "reusable_wait").put("format", "yaml")));
            repository.approve(secondRequest.observation().path("requestId").asText(), "user:owner");
            var disabled = gateway.execute(context, new ToolCall("disable", "skill.disable", Json.object()
                    .put("skillId", "reusable_wait").put("reason", "unexpected behavior")));
            assertEquals("SKILL_DISABLED", disabled.code());
            assertEquals("DISABLED", disabled.observation().path("status").asText());
            var rollback = gateway.execute(context, new ToolCall("rollback", "skill.rollback", Json.object()
                    .put("skillId", "reusable_wait").put("version", 1)
                    .put("reason", "restore reviewed version")));
            assertEquals("SKILL_ROLLED_BACK", rollback.code());
            assertEquals("ACTIVE", rollback.observation().path("status").asText());
            assertEquals(1, rollback.observation().path("version").asInt());

            var listed = gateway.execute(context, new ToolCall("list", "skill.list", Json.object()));
            assertTrue(listed.success());
            assertEquals(1, listed.observation().path("drafts").size());
            assertEquals(6, listed.observation().path("builtins").size());
            assertEquals(2, listed.observation().path("versions").size());
            var other = gateway.execute(new ToolContext("controller", "other-session", "companion-b"),
                    new ToolCall("other", "skill.list", Json.object()));
            assertTrue(other.observation().path("drafts").isEmpty());
            assertEquals(6, other.observation().path("builtins").size());
            assertTrue(other.observation().path("versions").isEmpty());
        }
    }

    @Test
    void executesOnlyActiveApprovedVersionThroughTaskGraphRuntime() throws Exception {
        AgentWorkspace workspace = new AgentWorkspace(temporary.resolve("execute-workspace"), "profile");
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("execute.db"))) {
            database.initialize();
            SkillRepository repository = new SkillRepository(database);
            SkillToolGateway[] holder = new SkillToolGateway[1];
            holder[0] = new SkillToolGateway(workspace, repository, "profile",
                    context -> holder[0].definitions(context));
            SkillToolGateway gateway = holder[0];
            try (TaskGraphRuntime runtime = new TaskGraphRuntime(gateway,
                    new TaskGraphExecutionRepository(database))) {
                gateway.attachTaskGraphRuntime(runtime);
                ToolContext context = new ToolContext("controller", "brain-session", "c1");
                String graph = """
                        version: mcac-task-graph/1
                        id: approved_return
                        permissions: []
                        root:
                          id: done
                          type: return
                          value: approved-result
                        """;
                gateway.execute(context, new ToolCall("save", "skill.save_draft", Json.object()
                        .put("skillId", "approved_return").put("format", "yaml").put("document", graph)));
                var pendingExecution = gateway.execute(context, new ToolCall("too-early", "skill.execute",
                        Json.object().put("skillId", "approved_return")));
                assertFalse(pendingExecution.success());
                assertEquals("INVALID_TOOL_ARGUMENTS", pendingExecution.code());

                var request = gateway.execute(context, new ToolCall("request", "skill.request_promotion",
                        Json.object().put("skillId", "approved_return").put("format", "yaml")));
                repository.approve(request.observation().path("requestId").asText(), "user:owner");
                ToolCall execute = new ToolCall("execute-approved", "skill.execute",
                        Json.object().put("skillId", "approved_return"));
                var accepted = gateway.execute(context, execute);
                assertTrue(accepted.success(), accepted.observation().toString());
                assertFalse(accepted.terminal());
                var terminal = gateway.awaitTerminal(context, execute, accepted, Duration.ofSeconds(5),
                        progress -> { });
                assertTrue(terminal.success(), terminal.observation().toString());
                assertEquals("SUCCEEDED", terminal.observation().path("state").asText());
                assertEquals("approved-result", terminal.observation().path("value").asText());
                assertEquals("APPROVED_GENERATED_SKILL",
                        new TaskGraphExecutionRepository(database).get("execute-approved").orElseThrow()
                                .provenance().path("source").asText());
            }
        }
    }

    @Test
    void invalidGraphRemainsQuarantinedAndUnknownFieldsAreRejected() throws Exception {
        AgentWorkspace workspace = new AgentWorkspace(temporary.resolve("invalid"), "profile");
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("invalid.db"))) {
            database.initialize();
            SkillToolGateway gateway = new SkillToolGateway(workspace, new SkillRepository(database), "profile",
                    context -> List.of());
            ToolContext context = new ToolContext("controller", "brain-session", "c1");
            assertTrue(gateway.execute(context, new ToolCall("save", "skill.save_draft", Json.object()
                    .put("skillId", "broken_skill").put("format", "json")
                    .put("document", "{\"version\":\"wrong\"}"))).success());
            var invalid = gateway.execute(context, new ToolCall("validate", "skill.validate", Json.object()
                    .put("skillId", "broken_skill").put("format", "json")));
            assertFalse(invalid.success());
            assertEquals("SKILL_INVALID", invalid.code());
            assertEquals("QUARANTINED", invalid.observation().path("trust").asText());
            assertEquals("SKILL_INVALID", gateway.execute(context, new ToolCall("promote",
                    "skill.request_promotion", Json.object()
                    .put("skillId", "broken_skill").put("format", "json"))).code());

            var unexpected = gateway.execute(context, new ToolCall("bad", "skill.list",
                    Json.object().put("path", "C:/source")));
            assertFalse(unexpected.success());
            assertEquals("INVALID_TOOL_ARGUMENTS", unexpected.code());
        }
    }
}
