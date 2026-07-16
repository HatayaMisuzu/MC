package com.mccompanion.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.capability.CapabilityRegistry;
import com.mccompanion.runtime.capability.CapabilityStatus;
import com.mccompanion.runtime.capability.CapabilityVisibility;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.Handshake;
import com.mccompanion.runtime.task.TaskRepository;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Read-only primitive observations derived from connected-body state and durable Runtime state. */
public final class ObservationToolGateway implements ToolGateway {
    private final CompanionRepository companions;
    private final TaskRepository tasks;
    private final CapabilityRegistry capabilities;
    private final CapabilityVisibility visibility;
    private final Function<String, Handshake> handshake;

    public ObservationToolGateway(CompanionRepository companions, TaskRepository tasks,
                                  CapabilityRegistry capabilities,
                                  Function<String, Handshake> handshake) {
        this.companions = java.util.Objects.requireNonNull(companions, "companions");
        this.tasks = java.util.Objects.requireNonNull(tasks, "tasks");
        this.capabilities = java.util.Objects.requireNonNull(capabilities, "capabilities");
        this.visibility = new CapabilityVisibility(capabilities);
        this.handshake = java.util.Objects.requireNonNull(handshake, "handshake");
    }

    @Override
    public List<ToolDefinition> definitions(ToolContext context) {
        return List.of(
                definition("world.query", "Read one bounded verified body-observation section",
                        querySchema(), "READ_WORLD"),
                definition("inventory.inspect", "Read the observed inventory counts and free slots",
                        emptySchema(), "INVENTORY"),
                definition("safety.inspect", "Read observed vitals and deterministic immediate hazard flags",
                        emptySchema(), "SURVIVAL"),
                definition("task.inspect", "Read the current durable task and recent state evidence",
                        emptySchema(), "CONTROL_TASK"),
                definition("capability.list", "List formal and connected-body capability lifecycle states",
                        emptySchema(), "READ_WORLD"),
                definition("capability.describe", "Describe one capability and its current lifecycle state",
                        capabilitySchema(), "READ_WORLD"));
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        try {
            return switch (call.name()) {
                case "world.query" -> query(context, call);
                case "inventory.inspect" -> inventory(context, call);
                case "safety.inspect" -> safety(context, call);
                case "task.inspect" -> task(context, call);
                case "capability.list" -> listCapabilities(context, call);
                case "capability.describe" -> describeCapability(context, call);
                default -> ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Observation tool is unavailable");
            };
        } catch (IllegalArgumentException failure) {
            return ToolResult.rejected(call, "INVALID_TOOL_ARGUMENTS", failure.getMessage());
        } catch (SQLException failure) {
            return ToolResult.rejected(call, "PERSISTENCE_ERROR", "Observed Runtime state is unavailable");
        }
    }

    private ToolResult query(ToolContext context, ToolCall call) throws SQLException {
        rejectUnexpected(call.arguments(), Set.of("select"));
        String select = call.arguments().path("select").asText("");
        if (!Set.of("position", "vitals", "inventory", "containers", "behavior", "all").contains(select)) {
            throw new IllegalArgumentException("select is invalid");
        }
        JsonNode status = status(context);
        JsonNode value = switch (select) {
            case "position" -> status.path("position");
            case "vitals" -> status.path("vitals");
            case "inventory" -> status.path("inventory");
            case "containers" -> status.path("observedContainers");
            case "behavior" -> behavior(status);
            case "all" -> status;
            default -> throw new IllegalStateException();
        };
        if (value.isMissingNode() || value.isNull()) {
            return ToolResult.rejected(call, "OBSERVATION_UNAVAILABLE",
                    "Selected body observation is not available");
        }
        ObjectNode observation = envelope(status, select);
        observation.set("value", value.deepCopy());
        return ok(call, observation);
    }

    private ToolResult inventory(ToolContext context, ToolCall call) throws SQLException {
        rejectUnexpected(call.arguments(), Set.of());
        JsonNode status = status(context);
        JsonNode inventory = status.path("inventory");
        if (!inventory.isObject()) {
            return ToolResult.rejected(call, "OBSERVATION_UNAVAILABLE",
                    "Inventory observation is not available");
        }
        ObjectNode observation = envelope(status, "inventory");
        observation.set("inventory", inventory.deepCopy());
        return ok(call, observation);
    }

    private ToolResult safety(ToolContext context, ToolCall call) throws SQLException {
        rejectUnexpected(call.arguments(), Set.of());
        JsonNode status = status(context);
        JsonNode vitals = status.path("vitals");
        if (!vitals.isObject()) {
            return ToolResult.rejected(call, "OBSERVATION_UNAVAILABLE", "Vitals observation is not available");
        }
        ArrayNode hazards = Json.MAPPER.createArrayNode();
        if (vitals.path("onFire").asBoolean()) hazards.add("ON_FIRE");
        if (vitals.path("inLava").asBoolean()) hazards.add("IN_LAVA");
        if (vitals.path("air").canConvertToInt() && vitals.path("air").asInt() < 40) hazards.add("LOW_AIR");
        double maximum = vitals.path("maxHealth").asDouble(0);
        double health = vitals.path("health").asDouble(maximum);
        if (maximum > 0 && health / maximum <= 0.3D) hazards.add("LOW_HEALTH");
        ObjectNode observation = envelope(status, "safety");
        observation.set("vitals", vitals.deepCopy());
        observation.set("hazards", hazards);
        observation.put("safe", hazards.isEmpty());
        observation.put("threatScanIncluded", false);
        return ok(call, observation);
    }

    private ToolResult task(ToolContext context, ToolCall call) throws SQLException {
        rejectUnexpected(call.arguments(), Set.of());
        ObjectNode observation = Json.object().put("companionId", context.companionId());
        var active = tasks.activeForCompanion(context.companionId());
        if (active.isEmpty()) return ok(call, observation.put("state", "IDLE"));
        observation.put("state", active.get().state().name());
        observation.set("task", Json.MAPPER.valueToTree(active.get()));
        List<com.mccompanion.runtime.task.TaskEvent> events = tasks.events(active.get().taskId());
        if (events.size() > 16) events = events.subList(events.size() - 16, events.size());
        observation.set("events", Json.MAPPER.valueToTree(events));
        return ok(call, observation);
    }

    private ToolResult listCapabilities(ToolContext context, ToolCall call) throws SQLException {
        rejectUnexpected(call.arguments(), Set.of());
        JsonNode status = status(context);
        var snapshot = visibility.resolve(handshake.apply(context.companionId()), status);
        ObjectNode observation = Json.object().put("companionId", context.companionId())
                .put("availableCount", snapshot.availableNames().size());
        observation.set("capabilities", Json.MAPPER.valueToTree(snapshot.statuses()));
        return ok(call, observation);
    }

    private ToolResult describeCapability(ToolContext context, ToolCall call) throws SQLException {
        rejectUnexpected(call.arguments(), Set.of("name"));
        String name = call.arguments().path("name").asText("");
        if (!name.matches("[A-Z][A-Za-z0-9]{2,63}")) throw new IllegalArgumentException("name is invalid");
        var definition = capabilities.find(name)
                .orElseThrow(() -> new IllegalArgumentException("capability is unknown"));
        JsonNode status = status(context);
        CapabilityStatus current = visibility.resolve(handshake.apply(context.companionId()), status).statuses()
                .stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
        ObjectNode observation = Json.object();
        observation.set("definition", Json.MAPPER.valueToTree(definition));
        observation.set("status", Json.MAPPER.valueToTree(current));
        return ok(call, observation);
    }

    private JsonNode status(ToolContext context) throws SQLException {
        return companions.get(context.companionId())
                .orElseThrow(() -> new IllegalArgumentException("companion is unknown")).status();
    }

    private static ObjectNode behavior(JsonNode status) {
        ObjectNode value = Json.object().put("bodyState", status.path("bodyState").asText(""))
                .put("behaviorState", status.path("behaviorState").asText("idle"))
                .put("behaviorRevision", status.path("behaviorRevision").asLong(0))
                .put("controlEpoch", status.path("controlEpoch").asLong(0));
        if (status.has("behaviorId")) value.put("behaviorId", status.path("behaviorId").asText());
        return value;
    }

    private static ObjectNode envelope(JsonNode status, String kind) {
        return Json.object().put("kind", kind).put("verified", true)
                .put("source", "CONNECTED_BODY_OBSERVATION")
                .put("dimension", status.path("dimension").asText(""))
                .put("observedAt", status.path("observedAt").asText(""));
    }

    private static ToolDefinition definition(String name, String description, JsonNode schema,
                                             String permission) {
        return new ToolDefinition(name, "1.0", description, schema, "LOW", permission,
                Duration.ofSeconds(5), true);
    }

    private static ObjectNode emptySchema() {
        return Json.object().put("type", "object").put("additionalProperties", false);
    }

    private static ObjectNode querySchema() {
        ObjectNode schema = emptySchema();
        schema.putObject("properties").putObject("select").put("type", "string").putArray("enum")
                .add("position").add("vitals").add("inventory").add("containers").add("behavior").add("all");
        schema.putArray("required").add("select");
        return schema;
    }

    private static ObjectNode capabilitySchema() {
        ObjectNode schema = emptySchema();
        schema.putObject("properties").putObject("name").put("type", "string")
                .put("pattern", "^[A-Z][A-Za-z0-9]{2,63}$");
        schema.putArray("required").add("name");
        return schema;
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
