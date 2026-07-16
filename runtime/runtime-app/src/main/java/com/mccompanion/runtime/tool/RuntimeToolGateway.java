package com.mccompanion.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.command.CommandReply;
import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.task.TaskType;
import com.mccompanion.runtime.task.TaskRecord;
import com.mccompanion.runtime.task.TaskRepository;
import com.mccompanion.runtime.task.TaskState;
import com.mccompanion.runtime.taskgraph.TaskGraphValidator;
import com.mccompanion.runtime.taskgraph.TaskGraphExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Production gateway: exposes only bounded MCAC operations, never shell/files/arbitrary URLs. */
public final class RuntimeToolGateway implements ToolGateway {
    private final CommandService commands;
    private final CompanionRepository companions;
    private final TaskRepository tasks;
    private final Function<String, List<String>> availableCapabilities;
    private final TaskGraphValidator taskGraphs = new TaskGraphValidator();
    private final java.util.concurrent.ConcurrentMap<String, String> activeTasks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RuntimeToolGateway(CommandService commands, CompanionRepository companions,
                              Function<String, List<String>> availableCapabilities) {
        this(commands, companions, null, availableCapabilities);
    }

    public RuntimeToolGateway(CommandService commands, CompanionRepository companions, TaskRepository tasks,
                              Function<String, List<String>> availableCapabilities) {
        this.commands = java.util.Objects.requireNonNull(commands, "commands");
        this.companions = java.util.Objects.requireNonNull(companions, "companions");
        this.tasks = tasks;
        this.availableCapabilities = java.util.Objects.requireNonNull(availableCapabilities, "availableCapabilities");
    }

    @Override public ToolResult awaitTerminal(ToolContext context, ToolCall call, ToolResult accepted,
                                              Duration timeout, java.util.function.Consumer<ToolResult> progress) {
        if (accepted.terminal() || !accepted.success() || tasks == null) return accepted;
        String taskId = accepted.observation().path("taskId").asText("");
        if (taskId.isBlank()) return ToolResult.rejected(call, "TOOL_BINDING_MISSING",
                "Accepted Minecraft tool did not return a durable task binding");
        long deadline = System.nanoTime() + Math.max(1L, timeout.toNanos());
        TaskState previous = null;
        try {
            while (System.nanoTime() < deadline) {
                TaskRecord task = tasks.get(taskId).orElse(null);
                if (task == null) return ToolResult.rejected(call, "TASK_NOT_FOUND", "Bound task disappeared");
                if (task.state().terminal() || task.state() == TaskState.BLOCKED
                        || task.state() == TaskState.PAUSED
                        || task.state() == TaskState.RECONCILIATION_REQUIRED) {
                    activeTasks.remove(key(context, call.callId()));
                    return terminalResult(call, task);
                }
                if (task.state() != previous) {
                    progress.accept(progressResult(call, task));
                    previous = task.state();
                }
                Thread.sleep(25);
            }
            CommandReply cancellation = commands.execute("brain-cancel-" + context.brainSessionId() + '-' + call.callId(),
                    context.companionId(), stop("cancel"));
            activeTasks.remove(key(context, call.callId()));
            long cancelDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (cancellation.accepted() && System.nanoTime() < cancelDeadline) {
                TaskRecord cancelled = tasks.get(taskId).orElse(null);
                if (cancelled == null) break;
                if (cancelled.state() == TaskState.CANCELLED) {
                    ToolResult confirmed = terminalResult(call, cancelled);
                    ObjectNode observation = (ObjectNode) confirmed.observation().deepCopy();
                    observation.put("timedOut", true).put("cancellationConfirmed", true);
                    return new ToolResult(call.callId(), call.name(), false, "TOOL_TIMEOUT_CANCELLED",
                            observation, true);
                }
                Thread.sleep(25);
            }
            return new ToolResult(call.callId(), call.name(), false, "TOOL_TIMEOUT",
                    Json.object().put("state", "INTERRUPTED").put("taskId", taskId)
                            .put("cancellationConfirmed", false)
                            .put("message", cancellation.accepted()
                                    ? "Tool timed out; cancellation was dispatched but not confirmed"
                                    : "Tool timed out; cancellation could not be dispatched"), true);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new ToolResult(call.callId(), call.name(), false, "TOOL_INTERRUPTED",
                    Json.object().put("state", "INTERRUPTED").put("taskId", taskId), true);
        } catch (java.sql.SQLException failure) {
            return ToolResult.rejected(call, "PERSISTENCE_ERROR", "Bound task state is unavailable");
        }
    }

    private ToolResult terminalResult(ToolCall call, TaskRecord task) throws java.sql.SQLException {
        TaskState state = task.state();
        ObjectNode observation = Json.object().put("state", switch (state) {
                    case COMPLETED -> "SUCCEEDED";
                    case FAILED -> "FAILED";
                    case CANCELLED -> "CANCELLED";
                    case BLOCKED, PAUSED -> "BLOCKED";
                    case RECONCILIATION_REQUIRED -> "INTERRUPTED";
                    default -> throw new IllegalStateException("Task is not terminal");
                }).put("taskId", task.taskId()).put("behaviorId", task.behaviorId())
                .put("taskRevision", task.revision()).put("behaviorRevision", task.behaviorRevision());
        List<com.mccompanion.runtime.task.TaskEvent> events = tasks.events(task.taskId());
        if (!events.isEmpty()) observation.set("fabricObservation", events.getLast().payload());
        boolean success = state == TaskState.COMPLETED;
        String code = success ? "OK" : state == TaskState.CANCELLED ? "TOOL_CANCELLED"
                : state == TaskState.BLOCKED || state == TaskState.PAUSED ? "TOOL_BLOCKED"
                : state == TaskState.RECONCILIATION_REQUIRED ? "TOOL_INTERRUPTED" : "TOOL_FAILED";
        return new ToolResult(call.callId(), call.name(), success, code, observation, true);
    }

    private ToolResult progressResult(ToolCall call, TaskRecord task) throws java.sql.SQLException {
        String state = switch (task.state()) {
            case CREATED, ACCEPTED -> "ACCEPTED";
            case RUNNING, WAITING -> "RUNNING";
            case PAUSED, BLOCKED -> "BLOCKED";
            case RECONCILIATION_REQUIRED -> "INTERRUPTED";
            case COMPLETED, FAILED, CANCELLED -> throw new IllegalStateException("Terminal task used as progress");
        };
        ObjectNode observation = Json.object().put("state", state).put("taskId", task.taskId())
                .put("behaviorId", task.behaviorId()).put("taskRevision", task.revision())
                .put("behaviorRevision", task.behaviorRevision());
        List<com.mccompanion.runtime.task.TaskEvent> events = tasks.events(task.taskId());
        if (!events.isEmpty()) observation.set("fabricObservation", events.getLast().payload());
        return new ToolResult(call.callId(), call.name(), true, "TOOL_PROGRESS", observation, false);
    }

    @Override public List<ToolDefinition> definitions(ToolContext context) {
        Set<String> available = Set.copyOf(availableCapabilities.apply(context.companionId()));
        List<ToolDefinition> values = new ArrayList<>();
        values.add(definition("world.observe", "Read the current verified companion status", Json.object(), "LOW", "READ_WORLD", true));
        values.add(definition("task.pause", "Pause the active task safely", Json.object(), "LOW", "CONTROL_TASK", false));
        values.add(definition("task.resume", "Resume a paused task", Json.object(), "LOW", "CONTROL_TASK", false));
        values.add(definition("task.cancel", "Cancel the active task", Json.object(), "LOW", "CONTROL_TASK", false));
        values.add(definition("task_graph.validate",
                "Statically validate a bounded declarative task graph without executing it",
                taskGraphSchema(), "LOW", "VALIDATE_TASK_GRAPH", true));
        values.add(definition("task_graph.execute",
                "Deterministically execute the currently supported nodes of a validated task graph",
                taskGraphSchema(), "MEDIUM", "EXECUTE_TASK_GRAPH", false));
        if (available.contains("FollowOwner")) values.add(definition("movement.follow", "Follow the owner", Json.object(), "LOW", "MOVE", false));
        if (available.contains("NavigateTo")) values.add(definition("movement.navigate", "Navigate in survival mode", coordinateSchema(), "LOW", "MOVE", false));
        if (available.contains("NavigateTo")) values.add(definition("movement.return", "Return to the owner", Json.object(), "LOW", "MOVE", false));
        if (available.contains("ExploreArea")) values.add(definition("world.scan",
                "Incrementally scan a bounded loaded area for one block type", scanSchema(), "MEDIUM", "READ_WORLD", true));
        if (available.contains("CollectResource")) values.add(definition("resource.collect",
                "Collect nearby dropped items through vanilla movement and pickup", itemQuantitySchema(),
                "MEDIUM", "COLLECT", false));
        if (available.contains("MineResourceVein")) values.add(definition("resource.mine_vein",
                "Mine a bounded connected vein through vanilla block breaking and collect its drops",
                mineSchema(), "MEDIUM", "MINE", false));
        if (available.contains("SmeltItem")) values.add(definition("item.smelt",
                "Smelt a bounded quantity in a verified nearby furnace using held input and fuel",
                smeltSchema(), "LOW", "CRAFT", false));
        if (available.contains("DefendOwner")) values.add(definition("combat.defend_owner",
                "Defend the owner from one nearby hostile using vanilla movement and attacks",
                Json.object(), "MEDIUM", "COMBAT", false));
        if (available.contains("WithdrawFromStorage")) values.add(definition("inventory.withdraw", "Withdraw from a verified container", withdrawSchema(), "LOW", "INVENTORY", false));
        if (available.contains("DepositToStorage")) values.add(definition("inventory.deposit", "Deposit held items into a verified container", withdrawSchema(), "LOW", "INVENTORY", false));
        if (available.contains("CraftItem")) values.add(definition("item.craft", "Craft an item through a vanilla crafting menu", craftSchema(), "LOW", "CRAFT", false));
        if (available.contains("DeliverItem")) values.add(definition("inventory.deliver", "Deliver held items to the owner", itemQuantitySchema(), "LOW", "INVENTORY", false));
        if (available.contains("EatAndRecover")) values.add(definition("item.eat_and_recover", "Eat food using normal game interaction", foodSchema(), "LOW", "SURVIVAL", false));
        return List.copyOf(values);
    }

    @Override public ToolResult execute(ToolContext context, ToolCall call) {
        boolean exposed = definitions(context).stream().anyMatch(value -> value.name().equals(call.name()));
        if (!exposed) return ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Tool is not AVAILABLE_NOW");
        try {
            if (call.name().equals("task_graph.validate")) {
                rejectUnexpected(call.arguments(), Set.of("graph"));
                JsonNode graph = call.arguments().path("graph");
                if (!graph.isObject()) throw new IllegalArgumentException("graph must be an object");
                Set<String> tools = definitions(context).stream().map(ToolDefinition::name)
                        .filter(name -> !name.equals("task_graph.validate")).collect(java.util.stream.Collectors.toSet());
                var validation = taskGraphs.validate(graph, tools);
                return new ToolResult(call.callId(), call.name(), validation.valid(),
                        validation.valid() ? "OK" : "TASK_GRAPH_INVALID", validation.toJson(), true);
            }
            if (call.name().equals("task_graph.execute")) {
                rejectUnexpected(call.arguments(), Set.of("graph"));
                JsonNode graph = call.arguments().path("graph");
                if (!graph.isObject()) throw new IllegalArgumentException("graph must be an object");
                var execution = new TaskGraphExecutor(this).execute(call.callId(), context, graph);
                return new ToolResult(call.callId(), call.name(), execution.success(), execution.code(),
                        execution.toJson(), true);
            }
            if (call.name().equals("world.observe")) {
                rejectUnexpected(call.arguments(), Set.of());
                JsonNode status = companions.get(context.companionId())
                        .orElseThrow(() -> new IllegalArgumentException("COMPANION_NOT_FOUND")).status();
                return new ToolResult(call.callId(), call.name(), true, "OK", status, true);
            }
            Intent intent = intent(call);
            String commandId = "brain-" + context.brainSessionId() + '-' + call.callId();
            CommandReply reply = commands.execute(commandId, context.companionId(), intent);
            ToolResult result = new ToolResult(call.callId(), call.name(), reply.accepted(), reply.code(),
                    reply.toJson(), !reply.accepted());
            if (reply.accepted() && reply.taskId() != null) {
                activeTasks.put(key(context, call.callId()), reply.taskId());
            }
            return result;
        } catch (IllegalArgumentException failure) {
            return ToolResult.rejected(call, "INVALID_TOOL_ARGUMENTS", failure.getMessage());
        } catch (java.sql.SQLException failure) {
            return ToolResult.rejected(call, "PERSISTENCE_ERROR", "Verified world state is unavailable");
        } catch (RuntimeException failure) {
            return ToolResult.rejected(call, "TOOL_GATEWAY_ERROR", "Tool execution stopped safely");
        }
    }

    @Override public void cancel(ToolContext context, String callId, String reason) {
        if (activeTasks.containsKey(key(context, callId))) {
            commands.execute("brain-cancel-" + context.brainSessionId() + '-' + callId,
                    context.companionId(), stop("cancel"));
        }
    }

    private static String key(ToolContext context, String callId) {
        return context.brainSessionId() + ':' + callId;
    }

    private static Intent intent(ToolCall call) {
        return switch (call.name()) {
            case "movement.follow" -> noArguments(call, TaskType.FOLLOW);
            case "movement.return" -> noArguments(call, TaskType.RETURN);
            case "movement.navigate" -> navigate(call.arguments());
            case "world.scan" -> skill("ExploreArea", validatedScan(call.arguments()));
            case "resource.collect" -> skill("CollectResource", validatedItemQuantity(call.arguments(), false));
            case "resource.mine_vein" -> skill("MineResourceVein", validatedMine(call.arguments()));
            case "item.smelt" -> skill("SmeltItem", validatedSmelt(call.arguments()));
            case "combat.defend_owner" -> skill("DefendOwner", noArgumentsNode(call.arguments()));
            case "inventory.withdraw" -> skill("WithdrawFromStorage", validatedWithdraw(call.arguments()));
            case "inventory.deposit" -> skill("DepositToStorage", validatedWithdraw(call.arguments()));
            case "item.craft" -> skill("CraftItem", validatedCraft(call.arguments()));
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

    private static JsonNode noArgumentsNode(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of());
        return arguments;
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

    private static JsonNode validatedCraft(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("item", "quantity", "allowPartial", "station"));
        String item = arguments.path("item").asText("");
        if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("item must be a namespaced item id");
        }
        if (!arguments.path("quantity").canConvertToInt()) {
            throw new IllegalArgumentException("quantity must be an integer");
        }
        int quantity = arguments.path("quantity").asInt();
        if (quantity < 1 || quantity > 2304) throw new IllegalArgumentException("quantity must be 1..2304");
        if (arguments.has("allowPartial") && !arguments.path("allowPartial").isBoolean()) {
            throw new IllegalArgumentException("allowPartial must be boolean");
        }
        if (!arguments.has("station")) return arguments;
        JsonNode station = arguments.path("station");
        if (!station.isObject()) throw new IllegalArgumentException("station must be an object");
        rejectUnexpected(station, Set.of("dimension", "x", "y", "z"));
        for (String field : List.of("x", "y", "z")) {
            if (!station.path(field).canConvertToInt()) {
                throw new IllegalArgumentException("station." + field + " must be an integer");
            }
        }
        int x = station.path("x").asInt(), y = station.path("y").asInt(), z = station.path("z").asInt();
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || y < -2048 || y > 2048) {
            throw new IllegalArgumentException("station coordinates are outside safe bounds");
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
        } else if (name.equals("inventory.deliver") || name.equals("item.craft")
                || name.equals("resource.collect")) {
            root.putArray("required").add("item").add("quantity");
        } else if (name.equals("world.scan")) {
            root.putArray("required").add("block").add("radius");
        } else if (name.equals("resource.mine_vein")) {
            root.putArray("required").add("block").add("maxBlocks").add("origin");
        } else if (name.equals("item.smelt")) {
            root.putArray("required").add("item").add("quantity").add("station");
        } else if (name.equals("task_graph.validate") || name.equals("task_graph.execute")) {
            root.putArray("required").add("graph");
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

    private static ObjectNode taskGraphSchema() {
        ObjectNode properties = Json.object();
        properties.putObject("graph").put("type", "object");
        return properties;
    }

    private static JsonNode validatedScan(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("block", "radius", "center"));
        String block = arguments.path("block").asText("");
        if (!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("block must be a namespaced block id");
        }
        if (!arguments.path("radius").canConvertToInt()) throw new IllegalArgumentException("radius must be an integer");
        int radius = arguments.path("radius").asInt();
        if (radius < 1 || radius > 16) throw new IllegalArgumentException("radius must be 1..16");
        ObjectNode values = Json.object().put("item", block).put("quantity", radius);
        if (arguments.has("center")) {
            JsonNode center = arguments.path("center");
            if (!center.isObject()) throw new IllegalArgumentException("center must be an object");
            rejectUnexpected(center, Set.of("dimension", "x", "y", "z"));
            for (String field : List.of("x", "y", "z")) {
                if (!center.path(field).canConvertToInt()) throw new IllegalArgumentException("center." + field + " must be an integer");
            }
            values.set("target", center.deepCopy());
        }
        return values;
    }

    private static ObjectNode scanSchema() {
        ObjectNode properties = Json.object();
        properties.putObject("block").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        properties.putObject("radius").put("type", "integer").put("minimum", 1).put("maximum", 16);
        ObjectNode center = properties.putObject("center");
        center.put("type", "object").put("additionalProperties", false);
        ObjectNode fields = center.putObject("properties");
        fields.putObject("dimension").put("type", "string");
        for (String field : List.of("x", "y", "z")) fields.putObject(field).put("type", "integer");
        center.putArray("required").add("x").add("y").add("z");
        return properties;
    }

    private static JsonNode validatedMine(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("block", "maxBlocks", "origin", "allowPartial"));
        String block = arguments.path("block").asText("");
        if (!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("block must be a namespaced block id");
        }
        if (!arguments.path("maxBlocks").canConvertToInt()) {
            throw new IllegalArgumentException("maxBlocks must be an integer");
        }
        int maximum = arguments.path("maxBlocks").asInt();
        if (maximum < 1 || maximum > 32) throw new IllegalArgumentException("maxBlocks must be 1..32");
        JsonNode origin = arguments.path("origin");
        if (!origin.isObject()) throw new IllegalArgumentException("origin must be an object");
        rejectUnexpected(origin, Set.of("dimension", "x", "y", "z"));
        for (String field : List.of("x", "y", "z")) {
            if (!origin.path(field).canConvertToInt()) throw new IllegalArgumentException("origin." + field + " must be an integer");
        }
        ObjectNode values = Json.object().put("item", block).put("quantity", maximum)
                .put("allowPartial", arguments.path("allowPartial").asBoolean(false));
        values.set("target", origin.deepCopy());
        return values;
    }

    private static ObjectNode mineSchema() {
        ObjectNode properties = Json.object();
        properties.putObject("block").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        properties.putObject("maxBlocks").put("type", "integer").put("minimum", 1).put("maximum", 32);
        properties.putObject("allowPartial").put("type", "boolean");
        ObjectNode origin = properties.putObject("origin");
        origin.put("type", "object").put("additionalProperties", false);
        ObjectNode fields = origin.putObject("properties");
        fields.putObject("dimension").put("type", "string");
        for (String field : List.of("x", "y", "z")) fields.putObject(field).put("type", "integer");
        origin.putArray("required").add("x").add("y").add("z");
        return properties;
    }

    private static JsonNode validatedSmelt(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("item", "quantity", "allowPartial", "station"));
        String item = arguments.path("item").asText("");
        if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("item must be a namespaced output item id");
        }
        if (!arguments.path("quantity").canConvertToInt()) {
            throw new IllegalArgumentException("quantity must be an integer");
        }
        int quantity = arguments.path("quantity").asInt();
        if (quantity < 1 || quantity > 64) throw new IllegalArgumentException("quantity must be 1..64");
        if (arguments.has("allowPartial") && !arguments.path("allowPartial").isBoolean()) {
            throw new IllegalArgumentException("allowPartial must be boolean");
        }
        JsonNode station = arguments.path("station");
        if (!station.isObject()) throw new IllegalArgumentException("station must be an object");
        rejectUnexpected(station, Set.of("dimension", "x", "y", "z"));
        for (String field : List.of("x", "y", "z")) {
            if (!station.path(field).canConvertToInt()) {
                throw new IllegalArgumentException("station." + field + " must be an integer");
            }
        }
        int x = station.path("x").asInt(), y = station.path("y").asInt(), z = station.path("z").asInt();
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || y < -2048 || y > 2048) {
            throw new IllegalArgumentException("station coordinates are outside safe bounds");
        }
        return arguments;
    }

    private static ObjectNode smeltSchema() {
        ObjectNode properties = Json.object();
        properties.putObject("item").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        properties.putObject("quantity").put("type", "integer").put("minimum", 1).put("maximum", 64);
        properties.putObject("allowPartial").put("type", "boolean");
        ObjectNode station = properties.putObject("station");
        station.put("type", "object").put("additionalProperties", false);
        ObjectNode fields = station.putObject("properties");
        fields.putObject("dimension").put("type", "string");
        for (String field : List.of("x", "y", "z")) fields.putObject(field).put("type", "integer");
        station.putArray("required").add("x").add("y").add("z");
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

    private static ObjectNode craftSchema() {
        ObjectNode properties = itemQuantitySchema();
        ObjectNode station = properties.putObject("station");
        station.put("type", "object").put("additionalProperties", false);
        ObjectNode fields = station.putObject("properties");
        fields.putObject("dimension").put("type", "string");
        for (String field : List.of("x", "y", "z")) fields.putObject(field).put("type", "integer");
        station.putArray("required").add("x").add("y").add("z");
        return properties;
    }
}
