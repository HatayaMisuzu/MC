package com.mccompanion.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.command.CommandReply;
import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.task.TaskType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Production gateway: exposes only bounded MCAC operations, never shell/files/arbitrary URLs. */
public final class RuntimeToolGateway implements ToolGateway {
    private final CommandService commands;
    private final CompanionRepository companions;
    private final Function<String, List<String>> availableCapabilities;

    public RuntimeToolGateway(CommandService commands, CompanionRepository companions,
                              Function<String, List<String>> availableCapabilities) {
        this.commands = java.util.Objects.requireNonNull(commands, "commands");
        this.companions = java.util.Objects.requireNonNull(companions, "companions");
        this.availableCapabilities = java.util.Objects.requireNonNull(availableCapabilities, "availableCapabilities");
    }

    @Override public List<ToolDefinition> definitions(ToolContext context) {
        Set<String> available = Set.copyOf(availableCapabilities.apply(context.companionId()));
        List<ToolDefinition> values = new ArrayList<>();
        values.add(definition("world.observe", "Read the current verified companion status", Json.object(), "LOW", "READ_WORLD", true));
        values.add(definition("task.pause", "Pause the active task safely", Json.object(), "LOW", "CONTROL_TASK", false));
        values.add(definition("task.resume", "Resume a paused task", Json.object(), "LOW", "CONTROL_TASK", false));
        values.add(definition("task.cancel", "Cancel the active task", Json.object(), "LOW", "CONTROL_TASK", false));
        if (available.contains("FollowOwner")) values.add(definition("movement.follow", "Follow the owner", Json.object(), "LOW", "MOVE", false));
        if (available.contains("NavigateTo")) values.add(definition("movement.navigate", "Navigate in survival mode", coordinateSchema(), "LOW", "MOVE", false));
        if (available.contains("NavigateTo")) values.add(definition("movement.return", "Return to the owner", Json.object(), "LOW", "MOVE", false));
        if (available.contains("WithdrawFromStorage")) values.add(definition("inventory.withdraw", "Withdraw from a verified container", withdrawSchema(), "LOW", "INVENTORY", false));
        if (available.contains("DepositToStorage")) values.add(definition("inventory.deposit", "Deposit held items into a verified container", withdrawSchema(), "LOW", "INVENTORY", false));
        if (available.contains("DeliverItem")) values.add(definition("inventory.deliver", "Deliver held items to the owner", itemQuantitySchema(), "LOW", "INVENTORY", false));
        if (available.contains("EatAndRecover")) values.add(definition("item.eat_and_recover", "Eat food using normal game interaction", foodSchema(), "LOW", "SURVIVAL", false));
        return List.copyOf(values);
    }

    @Override public ToolResult execute(ToolContext context, ToolCall call) {
        boolean exposed = definitions(context).stream().anyMatch(value -> value.name().equals(call.name()));
        if (!exposed) return ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Tool is not AVAILABLE_NOW");
        try {
            if (call.name().equals("world.observe")) {
                rejectUnexpected(call.arguments(), Set.of());
                JsonNode status = companions.get(context.companionId())
                        .orElseThrow(() -> new IllegalArgumentException("COMPANION_NOT_FOUND")).status();
                return new ToolResult(call.callId(), call.name(), true, "OK", status, true);
            }
            Intent intent = intent(call);
            String commandId = "brain-" + context.brainSessionId() + '-' + call.callId();
            CommandReply reply = commands.execute(commandId, context.companionId(), intent);
            return new ToolResult(call.callId(), call.name(), reply.accepted(), reply.code(), reply.toJson(),
                    !reply.accepted());
        } catch (IllegalArgumentException failure) {
            return ToolResult.rejected(call, "INVALID_TOOL_ARGUMENTS", failure.getMessage());
        } catch (java.sql.SQLException failure) {
            return ToolResult.rejected(call, "PERSISTENCE_ERROR", "Verified world state is unavailable");
        } catch (RuntimeException failure) {
            return ToolResult.rejected(call, "TOOL_GATEWAY_ERROR", "Tool execution stopped safely");
        }
    }

    private static Intent intent(ToolCall call) {
        return switch (call.name()) {
            case "movement.follow" -> noArguments(call, TaskType.FOLLOW);
            case "movement.return" -> noArguments(call, TaskType.RETURN);
            case "movement.navigate" -> navigate(call.arguments());
            case "inventory.withdraw" -> skill("WithdrawFromStorage", validatedWithdraw(call.arguments()));
            case "inventory.deposit" -> skill("DepositToStorage", validatedWithdraw(call.arguments()));
            case "inventory.deliver" -> skill("DeliverItem", validatedItemQuantity(call.arguments(), false));
            case "item.eat_and_recover" -> skill("EatAndRecover", validatedFood(call.arguments()));
            case "task.pause" -> noArgumentsStop(call, "pause");
            case "task.resume" -> noArgumentsStop(call, "resume");
            case "task.cancel" -> noArgumentsStop(call, "cancel");
            default -> throw new IllegalArgumentException("Unsupported tool");
        };
    }

    private static Intent noArguments(ToolCall call, TaskType type) {
        rejectUnexpected(call.arguments(), Set.of());
        return new Intent(type, Json.object(), call.name());
    }

    private static Intent noArgumentsStop(ToolCall call, String action) {
        rejectUnexpected(call.arguments(), Set.of());
        return stop(action);
    }

    private static Intent navigate(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("x", "y", "z", "dimension"));
        for (String field : List.of("x", "y", "z")) {
            if (!arguments.path(field).canConvertToInt()) throw new IllegalArgumentException(field + " must be an integer");
        }
        int x = arguments.path("x").asInt(), y = arguments.path("y").asInt(), z = arguments.path("z").asInt();
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || y < -2048 || y > 2048) {
            throw new IllegalArgumentException("coordinates are outside safe bounds");
        }
        ObjectNode target = Json.object().put("dimension", arguments.path("dimension").asText("minecraft:overworld"))
                .put("x", x).put("y", y).put("z", z);
        return new Intent(TaskType.TRAVEL, Json.object().set("target", target), "movement.navigate");
    }

    private static JsonNode validatedWithdraw(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("item", "quantity", "allowPartial", "container"));
        validatedItemQuantity(arguments, true);
        JsonNode container = arguments.path("container");
        if (!container.isObject()) throw new IllegalArgumentException("container is required");
        rejectUnexpected(container, Set.of("dimension", "x", "y", "z"));
        for (String field : List.of("x", "y", "z")) {
            if (!container.path(field).canConvertToInt()) throw new IllegalArgumentException("container." + field + " must be an integer");
        }
        int x = container.path("x").asInt(), y = container.path("y").asInt(), z = container.path("z").asInt();
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || y < -2048 || y > 2048) {
            throw new IllegalArgumentException("container coordinates are outside safe bounds");
        }
        return arguments;
    }

    private static JsonNode validatedItemQuantity(JsonNode arguments, boolean allowPartialSupported) {
        rejectUnexpected(arguments, allowPartialSupported
                ? Set.of("item", "quantity", "allowPartial", "container") : Set.of("item", "quantity", "allowPartial"));
        String item = arguments.path("item").asText("");
        if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("item must be a namespaced item id");
        if (!arguments.path("quantity").canConvertToInt()) throw new IllegalArgumentException("quantity must be an integer");
        int quantity = arguments.path("quantity").asInt();
        if (quantity < 1 || quantity > 2304) throw new IllegalArgumentException("quantity must be 1..2304");
        if (arguments.has("allowPartial") && !arguments.path("allowPartial").isBoolean()) {
            throw new IllegalArgumentException("allowPartial must be boolean");
        }
        return arguments;
    }

    private static JsonNode validatedFood(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("item"));
        if (arguments.has("item") && !arguments.path("item").asText("").matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("item must be a namespaced item id");
        }
        return arguments;
    }

    private static void rejectUnexpected(JsonNode object, Set<String> allowed) {
        if (!object.isObject()) throw new IllegalArgumentException("arguments must be an object");
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException("unexpected argument: " + field);
        });
    }

    private static Intent skill(String capability, JsonNode parameters) {
        ObjectNode arguments = Json.object().put("capability", capability);
        arguments.set("parameters", parameters);
        return new Intent(TaskType.SKILL, arguments, capability);
    }

    private static Intent stop(String action) {
        return new Intent(TaskType.STOP, Json.object().put("action", action), "task." + action);
    }

    private static ToolDefinition definition(String name, String description, JsonNode schema,
                                             String risk, String permission, boolean idempotent) {
        ObjectNode root = Json.object().put("type", "object").put("additionalProperties", false);
        root.set("properties", schema);
        if (name.equals("movement.navigate")) {
            root.putArray("required").add("x").add("y").add("z");
        } else if (name.equals("inventory.withdraw") || name.equals("inventory.deposit")) {
            root.putArray("required").add("item").add("quantity").add("container");
        } else if (name.equals("inventory.deliver")) {
            root.putArray("required").add("item").add("quantity");
        }
        return new ToolDefinition(name, "1.0", description, root, risk, permission,
                Duration.ofSeconds(30), idempotent);
    }

    private static ObjectNode coordinateSchema() {
        ObjectNode properties = Json.object();
        for (String field : List.of("x", "y", "z")) properties.putObject(field).put("type", "integer");
        properties.putObject("dimension").put("type", "string");
        return properties;
    }

    private static ObjectNode itemQuantitySchema() {
        ObjectNode properties = Json.object();
        properties.putObject("item").put("type", "string").put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        properties.putObject("quantity").put("type", "integer").put("minimum", 1).put("maximum", 2304);
        properties.putObject("allowPartial").put("type", "boolean");
        return properties;
    }

    private static ObjectNode withdrawSchema() {
        ObjectNode properties = itemQuantitySchema();
        ObjectNode container = properties.putObject("container");
        container.put("type", "object").put("additionalProperties", false);
        ObjectNode fields = container.putObject("properties");
        fields.putObject("dimension").put("type", "string");
        for (String field : List.of("x", "y", "z")) fields.putObject(field).put("type", "integer");
        container.putArray("required").add("x").add("y").add("z");
        return properties;
    }

    private static ObjectNode foodSchema() {
        ObjectNode properties = Json.object();
        properties.putObject("item").put("type", "string").put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        return properties;
    }
}
