package com.mccompanion.runtime.workspace;

import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolGatewayTest {
    @TempDir Path temporary;

    @Test
    void savesReadsListsAndValidatesQuarantinedDeclarativeDraft() {
        AgentWorkspace workspace = new AgentWorkspace(temporary.resolve("workspace"), "profile");
        SkillToolGateway[] holder = new SkillToolGateway[1];
        holder[0] = new SkillToolGateway(workspace, context -> holder[0].definitions(context));
        SkillToolGateway gateway = holder[0];
        ToolContext context = new ToolContext("controller", "brain-session", "companion-a");
        assertEquals(List.of("skill.list", "skill.read", "skill.save_draft", "skill.validate"),
                gateway.definitions(context).stream().map(value -> value.name()).toList());

        String graph = """
                version: mcac-task-graph/1
                id: reusable_wait
                permissions: []
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

        var listed = gateway.execute(context, new ToolCall("list", "skill.list", Json.object()));
        assertTrue(listed.success());
        assertEquals(1, listed.observation().size());
        assertTrue(gateway.execute(new ToolContext("controller", "other-session", "companion-b"),
                new ToolCall("other", "skill.list", Json.object())).observation().isEmpty());
    }

    @Test
    void invalidGraphRemainsQuarantinedAndUnknownFieldsAreRejected() {
        AgentWorkspace workspace = new AgentWorkspace(temporary.resolve("invalid"), "profile");
        SkillToolGateway gateway = new SkillToolGateway(workspace, context -> List.of());
        ToolContext context = new ToolContext("controller", "brain-session", "c1");
        assertTrue(gateway.execute(context, new ToolCall("save", "skill.save_draft", Json.object()
                .put("skillId", "broken_skill").put("format", "json")
                .put("document", "{\"version\":\"wrong\"}"))).success());
        var invalid = gateway.execute(context, new ToolCall("validate", "skill.validate", Json.object()
                .put("skillId", "broken_skill").put("format", "json")));
        assertFalse(invalid.success());
        assertEquals("SKILL_INVALID", invalid.code());
        assertEquals("QUARANTINED", invalid.observation().path("trust").asText());

        var unexpected = gateway.execute(context, new ToolCall("bad", "skill.list",
                Json.object().put("path", "C:/source")));
        assertFalse(unexpected.success());
        assertEquals("INVALID_TOOL_ARGUMENTS", unexpected.code());
    }
}
