package com.mccompanion.runtime.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.taskgraph.TaskGraphCodec;
import com.mccompanion.runtime.taskgraph.TaskGraphExecutor;
import com.mccompanion.runtime.taskgraph.TaskGraphValidator;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Bounded declarative Skill draft access over the logical Agent Workspace. */
public final class SkillToolGateway implements ToolGateway {
    private static final Pattern SKILL_ID = Pattern.compile("[a-z][a-z0-9_-]{2,63}");
    private final AgentWorkspace workspace;
    private final Function<ToolContext, List<ToolDefinition>> availableTools;
    private final TaskGraphValidator validator = new TaskGraphValidator();

    public SkillToolGateway(AgentWorkspace workspace,
                            Function<ToolContext, List<ToolDefinition>> availableTools) {
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
        this.availableTools = java.util.Objects.requireNonNull(availableTools, "availableTools");
    }

    @Override
    public List<ToolDefinition> definitions(ToolContext context) {
        return List.of(
                definition("skill.list", "List logical Skill draft resources", listSchema(), true),
                definition("skill.read", "Read one logical Skill draft without a host path", readSchema(), true),
                definition("skill.save_draft", "Atomically save a quarantined declarative Skill draft",
                        saveSchema(), true),
                definition("skill.validate", "Validate a Skill draft against current Task Graph and Tool contracts",
                        readSchema(), true));
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        try {
            return switch (call.name()) {
                case "skill.list" -> list(context, call);
                case "skill.read" -> read(context, call);
                case "skill.save_draft" -> save(context, call);
                case "skill.validate" -> validate(context, call);
                default -> ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Skill tool is unavailable");
            };
        } catch (IllegalArgumentException failure) {
            return ToolResult.rejected(call, "INVALID_TOOL_ARGUMENTS", failure.getMessage());
        } catch (IOException failure) {
            return ToolResult.rejected(call, "WORKSPACE_IO_FAILED", "Workspace operation failed");
        }
    }

    private ToolResult list(ToolContext context, ToolCall call) throws IOException {
        rejectUnexpected(call.arguments(), Set.of());
        return ok(call, Json.MAPPER.valueToTree(workspace.list(context.companionId(), "skills/")));
    }

    private ToolResult read(ToolContext context, ToolCall call) throws IOException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "format"));
        String skillId = skillId(call.arguments());
        String format = format(call.arguments());
        return ok(call, Json.MAPPER.valueToTree(workspace.read(context.companionId(), path(skillId, format))));
    }

    private ToolResult save(ToolContext context, ToolCall call) throws IOException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "format", "document"));
        String skillId = skillId(call.arguments());
        String format = format(call.arguments());
        String document = text(call.arguments(), "document", 1, 65_536);
        WorkspaceResource resource = workspace.save(context.companionId(), path(skillId, format), document);
        ObjectNode observation = Json.MAPPER.valueToTree(resource);
        observation.put("trust", "QUARANTINED");
        return new ToolResult(call.callId(), call.name(), true, "SKILL_DRAFT_QUARANTINED",
                observation, true);
    }

    private ToolResult validate(ToolContext context, ToolCall call) throws IOException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "format"));
        String skillId = skillId(call.arguments());
        String format = format(call.arguments());
        WorkspaceDocument document = workspace.read(context.companionId(), path(skillId, format));
        JsonNode graph = TaskGraphCodec.parse(document.content(), format.equals("json")
                ? TaskGraphCodec.Format.JSON : TaskGraphCodec.Format.YAML);
        Map<String, ToolDefinition> definitions = availableTools.apply(context).stream()
                .collect(Collectors.toMap(ToolDefinition::name, value -> value, (left, right) -> left));
        var result = validator.validateExecutable(graph, definitions, TaskGraphExecutor.EXECUTABLE_NODE_TYPES);
        ObjectNode observation = Json.object().put("skillId", skillId).put("valid", result.valid())
                .put("sha256", document.resource().sha256()).put("version", document.resource().version())
                .put("trust", result.valid() ? "GENERATED_VALIDATED" : "QUARANTINED");
        observation.set("validation", Json.MAPPER.valueToTree(result));
        return new ToolResult(call.callId(), call.name(), result.valid(),
                result.valid() ? "SKILL_VALID" : "SKILL_INVALID", observation, true);
    }

    private static ToolDefinition definition(String name, String description, JsonNode schema,
                                             boolean idempotent) {
        return new ToolDefinition(name, "1.0", description, schema, "LOW", "MANAGE_SKILLS",
                Duration.ofSeconds(5), idempotent);
    }

    private static ObjectNode listSchema() {
        return Json.object().put("type", "object").put("additionalProperties", false);
    }

    private static ObjectNode readSchema() {
        ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("skillId").put("type", "string").put("minLength", 3).put("maxLength", 64);
        properties.putObject("format").put("type", "string").putArray("enum").add("yaml").add("yml").add("json");
        schema.putArray("required").add("skillId").add("format");
        return schema;
    }

    private static ObjectNode saveSchema() {
        ObjectNode schema = readSchema();
        schema.withObject("properties").putObject("document").put("type", "string")
                .put("minLength", 1).put("maxLength", 65_536);
        schema.withArray("required").add("document");
        return schema;
    }

    private static String skillId(JsonNode arguments) {
        String value = text(arguments, "skillId", 3, 64);
        if (!SKILL_ID.matcher(value).matches()) throw new IllegalArgumentException("skillId is invalid");
        return value;
    }

    private static String format(JsonNode arguments) {
        String value = text(arguments, "format", 3, 4).toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("yaml", "yml", "json").contains(value)) {
            throw new IllegalArgumentException("format is invalid");
        }
        return value;
    }

    private static String path(String skillId, String format) {
        return "skills/" + skillId + "/draft." + format;
    }

    private static String text(JsonNode arguments, String name, int minimum, int maximum) {
        JsonNode value = arguments.path(name);
        if (!value.isTextual()) throw new IllegalArgumentException(name + " must be text");
        String text = value.asText();
        if (text.length() < minimum || text.length() > maximum) {
            throw new IllegalArgumentException(name + " length is invalid");
        }
        return text;
    }

    private static void rejectUnexpected(JsonNode arguments, Set<String> allowed) {
        arguments.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw new IllegalArgumentException("unexpected field: " + name);
        });
    }

    private static ToolResult ok(ToolCall call, JsonNode observation) {
        return new ToolResult(call.callId(), call.name(), true, "OK", observation, true);
    }
}
