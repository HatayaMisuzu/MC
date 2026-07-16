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
import java.sql.SQLException;
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
    private final SkillRepository skills;
    private final String profileId;
    private final Function<ToolContext, List<ToolDefinition>> availableTools;
    private final TaskGraphValidator validator = new TaskGraphValidator();

    public SkillToolGateway(AgentWorkspace workspace, SkillRepository skills, String profileId,
                            Function<ToolContext, List<ToolDefinition>> availableTools) {
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
        this.skills = java.util.Objects.requireNonNull(skills, "skills");
        this.profileId = requiredIdentifier(profileId, "profileId");
        this.availableTools = java.util.Objects.requireNonNull(availableTools, "availableTools");
    }

    @Override
    public List<ToolDefinition> definitions(ToolContext context) {
        return List.of(
                definition("skill.list", "List logical Skill draft resources", listSchema(), true),
                definition("skill.read", "Read one logical Skill draft or durable version without a host path",
                        readSchema(), true),
                definition("skill.save_draft", "Atomically save a quarantined declarative Skill draft",
                        saveSchema(), true),
                definition("skill.validate", "Validate a Skill draft against current Task Graph and Tool contracts",
                        draftSchema(), true),
                definition("skill.request_promotion",
                        "Request user review of a validated Skill version; the Brain cannot approve it",
                        draftSchema(), true),
                definition("skill.disable", "Disable the active approved Skill version", disableSchema(), true),
                definition("skill.rollback", "Rollback to a previously approved Skill version",
                        rollbackSchema(), true));
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        try {
            return switch (call.name()) {
                case "skill.list" -> list(context, call);
                case "skill.read" -> read(context, call);
                case "skill.save_draft" -> save(context, call);
                case "skill.validate" -> validate(context, call);
                case "skill.request_promotion" -> requestPromotion(context, call);
                case "skill.disable" -> disable(context, call);
                case "skill.rollback" -> rollback(context, call);
                default -> ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Skill tool is unavailable");
            };
        } catch (IllegalArgumentException failure) {
            return ToolResult.rejected(call, "INVALID_TOOL_ARGUMENTS", failure.getMessage());
        } catch (IOException failure) {
            return ToolResult.rejected(call, "WORKSPACE_IO_FAILED", "Workspace operation failed");
        } catch (SQLException failure) {
            return ToolResult.rejected(call, "SKILL_PERSISTENCE_FAILED", "Skill lifecycle persistence failed");
        }
    }

    private ToolResult list(ToolContext context, ToolCall call) throws IOException, SQLException {
        rejectUnexpected(call.arguments(), Set.of());
        ObjectNode observation = Json.object();
        observation.set("drafts", Json.MAPPER.valueToTree(workspace.list(context.companionId(), "skills/")));
        observation.set("versions", Json.MAPPER.valueToTree(skills.list(profileId, context.companionId()).stream()
                .map(SkillToolGateway::withoutDocument).toList()));
        return ok(call, observation);
    }

    private ToolResult read(ToolContext context, ToolCall call) throws IOException, SQLException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "format", "version"));
        String skillId = skillId(call.arguments());
        String format = format(call.arguments());
        if (call.arguments().has("version")) {
            long version = integer(call.arguments(), "version", 1, Integer.MAX_VALUE);
            SkillVersion stored = skills.version(profileId, context.companionId(), skillId, version)
                    .orElseThrow(() -> new IllegalArgumentException("skill version does not exist"));
            if (!stored.format().equals(format)) throw new IllegalArgumentException("skill format does not match");
            return ok(call, Json.MAPPER.valueToTree(stored));
        }
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
        ValidatedDraft draft = validatedDraft(context, call.arguments());
        var result = draft.validation();
        ObjectNode observation = Json.object().put("skillId", draft.skillId()).put("valid", result.valid())
                .put("sha256", draft.document().resource().sha256())
                .put("version", draft.document().resource().version())
                .put("trust", result.valid() ? "GENERATED_VALIDATED" : "QUARANTINED");
        observation.set("validation", Json.MAPPER.valueToTree(result));
        return new ToolResult(call.callId(), call.name(), result.valid(),
                result.valid() ? "SKILL_VALID" : "SKILL_INVALID", observation, true);
    }

    private ToolResult requestPromotion(ToolContext context, ToolCall call) throws IOException, SQLException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "format"));
        ValidatedDraft draft = validatedDraft(context, call.arguments());
        if (!draft.validation().valid()) {
            return ToolResult.rejected(call, "SKILL_INVALID", "Skill must pass current validation before review");
        }
        JsonNode provenance = draft.graph().path("provenance").isMissingNode()
                ? Json.object() : draft.graph().path("provenance");
        SkillVersion requested = skills.requestPromotion(profileId, context.companionId(), draft.skillId(),
                draft.format(), draft.document().content(), draft.document().resource().sha256(),
                draft.graph().path("permissions").isMissingNode() ? Json.MAPPER.createArrayNode()
                        : draft.graph().path("permissions"),
                Json.object().put("controllerId", context.controllerId())
                        .put("brainSessionId", context.brainSessionId()).set("graph", provenance.deepCopy()),
                Json.MAPPER.valueToTree(draft.validation()), context.controllerId(), context.brainSessionId());
        ObjectNode observation = withoutDocument(requested);
        observation.put("trust", "GENERATED_VALIDATED");
        observation.put("requiresUserApproval", true);
        return new ToolResult(call.callId(), call.name(), true,
                requested.status().equals("ACTIVE") ? "SKILL_ALREADY_ACTIVE"
                        : "SKILL_PROMOTION_PENDING_USER_APPROVAL", observation, true);
    }

    private ToolResult disable(ToolContext context, ToolCall call) throws SQLException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "reason"));
        SkillVersion disabled = skills.disable(profileId, context.companionId(), skillId(call.arguments()),
                context.controllerId(), text(call.arguments(), "reason", 1, 256));
        return new ToolResult(call.callId(), call.name(), true, "SKILL_DISABLED",
                withoutDocument(disabled), true);
    }

    private ToolResult rollback(ToolContext context, ToolCall call) throws SQLException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "version", "reason"));
        SkillVersion active = skills.rollback(profileId, context.companionId(), skillId(call.arguments()),
                integer(call.arguments(), "version", 1, Integer.MAX_VALUE), context.controllerId(),
                text(call.arguments(), "reason", 1, 256));
        return new ToolResult(call.callId(), call.name(), true, "SKILL_ROLLED_BACK",
                withoutDocument(active), true);
    }

    private ValidatedDraft validatedDraft(ToolContext context, JsonNode arguments) throws IOException {
        String skillId = skillId(arguments);
        String format = format(arguments);
        WorkspaceDocument document = workspace.read(context.companionId(), path(skillId, format));
        JsonNode graph = TaskGraphCodec.parse(document.content(), format.equals("json")
                ? TaskGraphCodec.Format.JSON : TaskGraphCodec.Format.YAML);
        Map<String, ToolDefinition> definitions = availableTools.apply(context).stream()
                .collect(Collectors.toMap(ToolDefinition::name, value -> value, (left, right) -> left));
        var result = validator.validateExecutable(graph, definitions, TaskGraphExecutor.EXECUTABLE_NODE_TYPES);
        return new ValidatedDraft(skillId, format, document, graph, result);
    }

    private static ToolDefinition definition(String name, String description, JsonNode schema,
                                             boolean idempotent) {
        return new ToolDefinition(name, "1.0", description, schema, "LOW", "MANAGE_SKILLS",
                Duration.ofSeconds(5), idempotent);
    }

    private static ObjectNode listSchema() {
        return Json.object().put("type", "object").put("additionalProperties", false);
    }

    private static ObjectNode draftSchema() {
        ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("skillId").put("type", "string").put("minLength", 3).put("maxLength", 64);
        properties.putObject("format").put("type", "string").putArray("enum").add("yaml").add("yml").add("json");
        schema.putArray("required").add("skillId").add("format");
        return schema;
    }

    private static ObjectNode readSchema() {
        ObjectNode schema = draftSchema();
        schema.withObject("properties").putObject("version").put("type", "integer").put("minimum", 1);
        return schema;
    }

    private static ObjectNode saveSchema() {
        ObjectNode schema = draftSchema();
        schema.withObject("properties").putObject("document").put("type", "string")
                .put("minLength", 1).put("maxLength", 65_536);
        schema.withArray("required").add("document");
        return schema;
    }

    private static ObjectNode disableSchema() {
        ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("skillId").put("type", "string").put("minLength", 3).put("maxLength", 64);
        properties.putObject("reason").put("type", "string").put("minLength", 1).put("maxLength", 256);
        schema.putArray("required").add("skillId").add("reason");
        return schema;
    }

    private static ObjectNode rollbackSchema() {
        ObjectNode schema = disableSchema();
        schema.withObject("properties").putObject("version").put("type", "integer").put("minimum", 1);
        schema.withArray("required").add("version");
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

    private static long integer(JsonNode arguments, String name, long minimum, long maximum) {
        JsonNode value = arguments.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        long number = value.asLong();
        if (number < minimum || number > maximum) throw new IllegalArgumentException(name + " is out of range");
        return number;
    }

    private static String requiredIdentifier(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.strip();
    }

    private static ObjectNode withoutDocument(SkillVersion value) {
        ObjectNode node = Json.MAPPER.valueToTree(value);
        node.remove("document");
        return node;
    }

    private static void rejectUnexpected(JsonNode arguments, Set<String> allowed) {
        arguments.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw new IllegalArgumentException("unexpected field: " + name);
        });
    }

    private static ToolResult ok(ToolCall call, JsonNode observation) {
        return new ToolResult(call.callId(), call.name(), true, "OK", observation, true);
    }

    private record ValidatedDraft(String skillId, String format, WorkspaceDocument document, JsonNode graph,
                                  com.mccompanion.runtime.taskgraph.TaskGraphValidationResult validation) {
    }
}
