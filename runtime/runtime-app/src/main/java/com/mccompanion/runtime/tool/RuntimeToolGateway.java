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
import com.mccompanion.runtime.taskgraph.TaskGraphCodec;
import com.mccompanion.runtime.taskgraph.TaskGraphRuntime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Production gateway: exposes only bounded MCAC operations, never shell/files/arbitrary URLs. */
public final class RuntimeToolGateway implements ToolGateway, AutoCloseable {
    private final CommandService commands;
    private final CompanionRepository companions;
    private final TaskRepository tasks;
    private final Function<String, List<String>> availableCapabilities;
    private final Duration cancellationConfirmationTimeout;
    private final TaskGraphValidator taskGraphs = new TaskGraphValidator();
    private volatile TaskGraphRuntime taskGraphRuntime;
    private final java.util.concurrent.ConcurrentMap<String, String> activeTasks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RuntimeToolGateway(CommandService commands, CompanionRepository companions,
                              Function<String, List<String>> availableCapabilities) {
        this(commands, companions, null, availableCapabilities);
    }

    public RuntimeToolGateway(CommandService commands, CompanionRepository companions, TaskRepository tasks,
                              Function<String, List<String>> availableCapabilities) {
        this(commands, companions, tasks, availableCapabilities, Duration.ofSeconds(5));
    }

    RuntimeToolGateway(CommandService commands, CompanionRepository companions, TaskRepository tasks,
                       Function<String, List<String>> availableCapabilities,
                       Duration cancellationConfirmationTimeout) {
        this.commands = java.util.Objects.requireNonNull(commands, "commands");
        this.companions = java.util.Objects.requireNonNull(companions, "companions");
        this.tasks = tasks;
        this.availableCapabilities = java.util.Objects.requireNonNull(availableCapabilities, "availableCapabilities");
        this.cancellationConfirmationTimeout = java.util.Objects.requireNonNull(
                cancellationConfirmationTimeout, "cancellationConfirmationTimeout");
        if (cancellationConfirmationTimeout.isNegative() || cancellationConfirmationTimeout.isZero()) {
            throw new IllegalArgumentException("cancellationConfirmationTimeout must be positive");
        }
    }

    public void attachTaskGraphRuntime(TaskGraphRuntime runtime) {
        if (taskGraphRuntime != null) throw new IllegalStateException("Task Graph Runtime is already attached");
        taskGraphRuntime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    @Override public ToolResult awaitTerminal(ToolContext context, ToolCall call, ToolResult accepted,
                                              Duration timeout, java.util.function.Consumer<ToolResult> progress) {
        if (call.name().equals("task_graph.execute") && taskGraphRuntime != null && !accepted.terminal()) {
            return taskGraphRuntime.await(context, call, timeout, progress);
        }
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
            long cancelDeadline = System.nanoTime() + cancellationConfirmationTimeout.toNanos();
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
            TaskRecord reconciled = markCancellationUnconfirmed(taskId, cancellation);
            return new ToolResult(call.callId(), call.name(), false,
                    "TOOL_TIMEOUT_RECONCILIATION_REQUIRED",
                    Json.object().put("state", "RECONCILIATION_REQUIRED").put("taskId", taskId)
                            .put("taskRevision", reconciled.revision()).put("timedOut", true)
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

    private TaskRecord markCancellationUnconfirmed(String taskId, CommandReply cancellation)
            throws java.sql.SQLException, InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            TaskRecord task = tasks.get(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Bound task disappeared"));
            if (task.state().terminal() || task.state() == TaskState.RECONCILIATION_REQUIRED) return task;
            ObjectNode evidence = Json.object()
                    .put("code", "TOOL_TIMEOUT_RECONCILIATION_REQUIRED")
                    .put("cancellationAccepted", cancellation.accepted())
                    .put("message", cancellation.accepted()
                            ? "Tool cancellation was dispatched but not durably confirmed"
                            : "Tool cancellation could not be dispatched");
            try {
                return tasks.transition(task.taskId(), task.revision(), TaskState.RECONCILIATION_REQUIRED,
                        "ToolTimeoutCancellationUnconfirmed", evidence);
            } catch (TaskRepository.StaleTaskRevisionException stale) {
                Thread.sleep(5);
            }
        }
        throw new java.sql.SQLException("Unable to persist Tool cancellation reconciliation state");
    }

    private ToolResult terminalResult(ToolCall call, TaskRecord task) throws java.sql.SQLException {
        TaskState state = task.state();
        ObjectNode observation = Json.object().put("state", switch (state) {
                    case COMPLETED -> "SUCCEEDED";
                    case FAILED -> "FAILED";
                    case CANCELLED -> "CANCELLED";
                    case BLOCKED, PAUSED -> "BLOCKED";
                    case RECONCILIATION_REQUIRED -> "RECONCILIATION_REQUIRED";
                    default -> throw new IllegalStateException("Task is not terminal");
                }).put("taskId", task.taskId()).put("behaviorId", task.behaviorId())
                .put("taskRevision", task.revision()).put("behaviorRevision", task.behaviorRevision());
        List<com.mccompanion.runtime.task.TaskEvent> events = tasks.events(task.taskId());
        if (!events.isEmpty()) observation.set("fabricObservation", events.getLast().payload());
        boolean success = state == TaskState.COMPLETED;
        String code = success ? "OK" : state == TaskState.CANCELLED ? "TOOL_CANCELLED"
                : state == TaskState.BLOCKED || state == TaskState.PAUSED ? "TOOL_BLOCKED"
                : state == TaskState.RECONCILIATION_REQUIRED ? "TOOL_RECONCILIATION_REQUIRED" : "TOOL_FAILED";
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
                "Start a persistent asynchronous deterministic task graph execution",
                taskGraphSchema(), "MEDIUM", "EXECUTE_TASK_GRAPH", false));
        if (taskGraphRuntime != null) {
            values.add(definition("task_graph.inspect", "Inspect a session-owned task graph execution",
                    executionIdSchema(), "LOW", "CONTROL_TASK", true));
            values.add(definition("task_graph.pause", "Pause a session-owned task graph execution",
                    executionIdSchema(), "LOW", "CONTROL_TASK", false));
            values.add(definition("task_graph.resume", "Resume a safely paused task graph execution",
                    executionIdSchema(), "LOW", "CONTROL_TASK", false));
            values.add(definition("task_graph.cancel", "Cancel a session-owned task graph execution",
                    executionIdSchema(), "LOW", "CONTROL_TASK", false));
        }
        if (available.contains("FollowOwner")) values.add(definition("movement.follow", "Follow the owner", Json.object(), "LOW", "MOVE", false));
        if (available.contains("NavigateTo")) values.add(definition("movement.navigate", "Navigate in survival mode", coordinateSchema(), "LOW", "MOVE", false));
        if (available.contains("NavigateTo")) values.add(definition("movement.return", "Return to the owner", Json.object(), "LOW", "MOVE", false));
        if (available.contains("NavigateTo")) values.add(definition("movement.step",
                "Move a bounded relative step through normal navigation", stepSchema(), "LOW", "MOVE", false));
        if (available.contains("LookAt")) values.add(definition("movement.look",
                "Turn the connected body toward one bounded world position", lookSchema(), "LOW", "MOVE", true));
        if (available.contains("NavigateTo") && tasks != null) values.add(definition("movement.stop",
                "Cancel only an active movement task", Json.object(), "LOW", "MOVE", false));
        if (available.contains("ExploreArea")) values.add(definition("world.scan",
                "Incrementally scan a bounded loaded area for one block type", scanSchema(), "MEDIUM", "READ_WORLD", true));
        if (available.contains("CollectResource")) values.add(definition("resource.collect",
                "Collect nearby dropped items through vanilla movement and pickup", itemQuantitySchema(),
                "MEDIUM", "COLLECT", false));
        if (available.contains("CollectResource")) values.add(definition("entity.collect",
                "Collect a bounded nearby item entity through vanilla movement and pickup", itemQuantitySchema(),
                "MEDIUM", "COLLECT", false));
        if (available.contains("MineResourceVein")) values.add(definition("resource.mine_vein",
                "Mine a bounded connected vein through vanilla block breaking and collect its drops",
                mineSchema(), "MEDIUM", "MINE", false));
        if (available.contains("MineResourceVein")) values.add(definition("block.break",
                "Break one observed block through vanilla block-breaking rules", blockBreakSchema(),
                "MEDIUM", "MINE", false));
        if (available.contains("InteractBlock")) values.add(definition("block.interact",
                "Interact once with a visible reachable block through vanilla player rules",
                blockInteractionSchema(), "LOW", "INTERACT", false));
        if (available.contains("PlaceBlock")) values.add(definition("block.place",
                "Place one declared block at an exact reachable position through vanilla player rules",
                blockPlaceSchema(), "MEDIUM", "BUILD", false));
        if (available.contains("InteractEntity")) values.add(definition("entity.interact",
                "Interact once with a visible reachable entity through vanilla player rules",
                entityInteractionSchema(), "LOW", "INTERACT", false));
        if (available.contains("AttackEntity")) values.add(definition("entity.attack",
                "Attack one externally selected visible reachable living entity through vanilla player rules",
                entityAttackSchema(), "MEDIUM", "COMBAT", false));
        if (available.contains("MenuAction")) {
            values.add(definition("menu.click",
                    "Perform one bounded pickup click in the exact short-lived open menu session",
                    menuClickSchema(), "LOW", "INVENTORY", false));
            values.add(definition("menu.quick_move",
                    "Quick-move one slot in the exact short-lived open menu session",
                    menuSlotSchema(), "LOW", "INVENTORY", false));
            values.add(definition("menu.close",
                    "Close the exact short-lived open menu session",
                    menuCloseSchema(), "LOW", "INVENTORY", false));
        }
        if (available.contains("SmeltItem")) values.add(definition("item.smelt",
                "Smelt a bounded quantity in a verified nearby furnace using held input and fuel",
                smeltSchema(), "LOW", "CRAFT", false));
        if (available.contains("UseItem")) values.add(definition("item.use",
                "Use one held namespaced item through vanilla player interaction for a bounded duration",
                itemUseSchema(), "LOW", "INTERACT", false));
        if (available.contains("DefendOwner")) values.add(definition("combat.defend_owner",
                "Defend the owner from one nearby hostile using vanilla movement and attacks",
                Json.object(), "MEDIUM", "COMBAT", false));
        if (available.contains("RetreatFromDanger")) values.add(definition("safety.retreat",
                "Retreat from one externally selected live threat using vanilla player movement",
                entityAttackSchema(), "LOW", "SURVIVAL", false));
        if (available.contains("WithdrawFromStorage")) values.add(definition("inventory.withdraw", "Withdraw from a verified container", withdrawSchema(), "LOW", "INVENTORY", false));
        if (available.contains("DepositToStorage")) values.add(definition("inventory.deposit", "Deposit held items into a verified container", withdrawSchema(), "LOW", "INVENTORY", false));
        if (available.contains("WithdrawFromStorage") && available.contains("DepositToStorage")) {
            values.add(definition("inventory.transfer",
                    "Transfer a bounded item quantity between inventory and one verified container",
                    transferSchema(), "LOW", "INVENTORY", false));
        }
        if (available.contains("CraftItem")) values.add(definition("item.craft", "Craft an item through a vanilla crafting menu", craftSchema(), "LOW", "CRAFT", false));
        if (available.contains("DeliverItem")) values.add(definition("inventory.deliver", "Deliver held items to the owner", itemQuantitySchema(), "LOW", "INVENTORY", false));
        if (available.contains("DropItem")) values.add(definition("inventory.drop",
                "Drop a bounded quantity from the connected body through vanilla player rules",
                dropSchema(), "MEDIUM", "INVENTORY", false));
        if (available.contains("EatAndRecover")) values.add(definition("item.eat_and_recover", "Eat food using normal game interaction", foodSchema(), "LOW", "SURVIVAL", false));
        return List.copyOf(values);
    }

    @Override public ToolResult execute(ToolContext context, ToolCall call) {
        boolean exposed = definitions(context).stream().anyMatch(value -> value.name().equals(call.name()));
        if (!exposed) return ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Tool is not AVAILABLE_NOW");
        try {
            if (call.name().equals("task_graph.validate")) {
                JsonNode graph = parsedTaskGraph(call.arguments());
                var tools = definitions(context).stream()
                        .filter(value -> !value.name().startsWith("task_graph."))
                        .collect(java.util.stream.Collectors.toMap(ToolDefinition::name, value -> value));
                var validation = taskGraphs.validateExecutable(graph, tools,
                        taskGraphRuntime == null ? TaskGraphExecutor.EXECUTABLE_NODE_TYPES
                                : taskGraphRuntime.executableNodeTypes());
                return new ToolResult(call.callId(), call.name(), validation.valid(),
                        validation.valid() ? "OK" : "TASK_GRAPH_INVALID", validation.toJson(), true);
            }
            if (call.name().equals("task_graph.execute")) {
                JsonNode graph = parsedTaskGraph(call.arguments());
                if (taskGraphRuntime == null) {
                    return ToolResult.rejected(call, "TASK_GRAPH_RUNTIME_UNAVAILABLE",
                            "persistent Task Graph Runtime is unavailable");
                }
                return taskGraphRuntime.start(context, call, graph, call.arguments().path("inputs"),
                        call.arguments().path("provenance"));
            }
            if (call.name().startsWith("task_graph.") && !call.name().equals("task_graph.validate")) {
                if (taskGraphRuntime == null) {
                    return ToolResult.rejected(call, "TASK_GRAPH_RUNTIME_UNAVAILABLE",
                            "persistent Task Graph Runtime is unavailable");
                }
                rejectUnexpected(call.arguments(), Set.of("executionId"));
                String executionId = call.arguments().path("executionId").asText("");
                if (executionId.isBlank()) throw new IllegalArgumentException("executionId is required");
                return switch (call.name()) {
                    case "task_graph.inspect" -> taskGraphRuntime.inspect(context, call, executionId);
                    case "task_graph.pause" -> taskGraphRuntime.pause(context, call, executionId);
                    case "task_graph.resume" -> taskGraphRuntime.resume(context, call, executionId);
                    case "task_graph.cancel" ->
                            taskGraphRuntime.cancel(context, call, executionId, "external cancel");
                    default -> throw new IllegalArgumentException("Unsupported Task Graph control");
                };
            }
            if (call.name().equals("world.observe")) {
                rejectUnexpected(call.arguments(), Set.of());
                JsonNode status = companions.get(context.companionId())
                        .orElseThrow(() -> new IllegalArgumentException("COMPANION_NOT_FOUND")).status();
                return new ToolResult(call.callId(), call.name(), true, "OK", status, true);
            }
            Intent intent;
            if (call.name().equals("movement.step")) {
                intent = step(context, call.arguments());
            } else if (call.name().equals("movement.stop")) {
                intent = movementStop(context, call.arguments());
            } else {
                intent = intent(call);
            }
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
        if (taskGraphRuntime != null) taskGraphRuntime.cancel(context, callId, reason);
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
            case "movement.look" -> skill("LookAt", validatedLook(call.arguments()));
            case "block.break" -> breakBlock(call.arguments());
            case "block.interact" -> skill("InteractBlock", validatedBlockInteraction(call.arguments()));
            case "block.place" -> skill("PlaceBlock", validatedBlockPlacement(call.arguments()));
            case "entity.interact" -> skill("InteractEntity", validatedEntityInteraction(call.arguments()));
            case "entity.attack" -> skill("AttackEntity", validatedEntityAttack(call.arguments()));
            case "menu.click" -> skill("MenuAction", validatedMenuAction(call.arguments(), "CLICK"));
            case "menu.quick_move" -> skill("MenuAction", validatedMenuAction(call.arguments(), "QUICK_MOVE"));
            case "menu.close" -> skill("MenuAction", validatedMenuAction(call.arguments(), "CLOSE"));
            case "world.scan" -> skill("ExploreArea", validatedScan(call.arguments()));
            case "resource.collect" -> skill("CollectResource", validatedItemQuantity(call.arguments(), false));
            case "entity.collect" -> skill("CollectResource", validatedItemQuantity(call.arguments(), false));
            case "resource.mine_vein" -> skill("MineResourceVein", validatedMine(call.arguments()));
            case "item.smelt" -> skill("SmeltItem", validatedSmelt(call.arguments()));
            case "item.use" -> skill("UseItem", validatedItemUse(call.arguments()));
            case "combat.defend_owner" -> skill("DefendOwner", noArgumentsNode(call.arguments()));
            case "safety.retreat" -> skill("RetreatFromDanger", validatedEntityAttack(call.arguments()));
            case "inventory.withdraw" -> skill("WithdrawFromStorage", validatedWithdraw(call.arguments()));
            case "inventory.deposit" -> skill("DepositToStorage", validatedWithdraw(call.arguments()));
            case "inventory.transfer" -> transfer(call.arguments());
            case "item.craft" -> skill("CraftItem", validatedCraft(call.arguments()));
            case "inventory.deliver" -> skill("DeliverItem", validatedItemQuantity(call.arguments(), false));
            case "inventory.drop" -> skill("DropItem", validatedDrop(call.arguments()));
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

    private static JsonNode validatedLook(JsonNode arguments) {
        Intent target = navigate(arguments);
        return Json.object().set("target", target.arguments().path("target"));
    }

    private static JsonNode validatedBlockInteraction(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("position", "face", "hand"));
        JsonNode position = arguments.path("position");
        if (!position.isObject()) throw new IllegalArgumentException("position must be an object");
        rejectUnexpected(position, Set.of("dimension", "x", "y", "z"));
        ObjectNode target = validatedBoundedPosition(position);
        String face = enumValue(arguments.path("face").asText("UP"), "face",
                Set.of("DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"));
        String hand = enumValue(arguments.path("hand").asText("MAIN_HAND"), "hand",
                Set.of("MAIN_HAND", "OFF_HAND"));
        return Json.object().put("face", face).put("hand", hand).set("target", target);
    }

    private static JsonNode validatedBlockPlacement(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("block", "position", "face", "hand"));
        String block = arguments.path("block").asText("");
        if (!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("block must be a namespaced block id");
        }
        JsonNode position = arguments.path("position");
        if (!position.isObject()) throw new IllegalArgumentException("position must be an object");
        rejectUnexpected(position, Set.of("dimension", "x", "y", "z"));
        ObjectNode target = validatedBoundedPosition(position);
        String face = enumValue(arguments.path("face").asText("UP"), "face",
                Set.of("DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"));
        String hand = enumValue(arguments.path("hand").asText("MAIN_HAND"), "hand",
                Set.of("MAIN_HAND", "OFF_HAND"));
        return Json.object().put("item", block).put("face", face).put("hand", hand).set("target", target);
    }

    private static JsonNode validatedEntityInteraction(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("entityId", "hand"));
        String entityId = arguments.path("entityId").asText("");
        try {
            entityId = java.util.UUID.fromString(entityId).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("entityId must be a UUID");
        }
        String hand = enumValue(arguments.path("hand").asText("MAIN_HAND"), "hand",
                Set.of("MAIN_HAND", "OFF_HAND"));
        return Json.object().put("entityId", entityId).put("hand", hand);
    }

    private static JsonNode validatedEntityAttack(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("entityId"));
        String entityId = arguments.path("entityId").asText("");
        try {
            entityId = java.util.UUID.fromString(entityId).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("entityId must be a UUID");
        }
        return Json.object().put("entityId", entityId);
    }

    private static JsonNode validatedMenuAction(JsonNode arguments, String action) {
        Set<String> allowed = action.equals("CLICK")
                ? Set.of("sessionToken", "slot", "button")
                : action.equals("QUICK_MOVE") ? Set.of("sessionToken", "slot")
                : Set.of("sessionToken");
        rejectUnexpected(arguments, allowed);
        String token = arguments.path("sessionToken").asText("");
        if (!token.matches("[A-Za-z0-9_-]{32}")) {
            throw new IllegalArgumentException("sessionToken must be a 32-character opaque menu capability");
        }
        ObjectNode values = Json.object().put("sessionToken", token).put("action", action);
        if (!action.equals("CLOSE")) {
            if (!arguments.path("slot").canConvertToInt()) {
                throw new IllegalArgumentException("slot must be an integer");
            }
            int slot = arguments.path("slot").asInt();
            if (slot < 0 || slot > 127) throw new IllegalArgumentException("slot must be between 0 and 127");
            values.put("slot", slot);
        }
        if (action.equals("CLICK")) {
            if (!arguments.path("button").canConvertToInt()) {
                throw new IllegalArgumentException("button must be an integer");
            }
            int button = arguments.path("button").asInt();
            if (button < 0 || button > 1) throw new IllegalArgumentException("button must be 0 or 1");
            values.put("button", button);
        }
        return values;
    }

    private static JsonNode validatedItemUse(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("item", "hand", "durationTicks"));
        String item = namespacedId(arguments.path("item").asText(""), "item");
        String hand = enumValue(arguments.path("hand").asText("MAIN_HAND"), "hand",
                Set.of("MAIN_HAND", "OFF_HAND"));
        int durationTicks = arguments.path("durationTicks").asInt(0);
        if (!arguments.path("durationTicks").isMissingNode()
                && !arguments.path("durationTicks").canConvertToInt()) {
            throw new IllegalArgumentException("durationTicks must be an integer");
        }
        if (durationTicks < 0 || durationTicks > 720) {
            throw new IllegalArgumentException("durationTicks must be between 0 and 720");
        }
        return Json.object().put("item", item).put("hand", hand).put("durationTicks", durationTicks);
    }

    private static JsonNode validatedDrop(JsonNode arguments) {
        ObjectNode values = (ObjectNode) validatedItemQuantity(arguments, false);
        if (values.path("quantity").asInt() > 64) {
            throw new IllegalArgumentException("quantity must be between 1 and 64");
        }
        return values;
    }

    private static ObjectNode validatedBoundedPosition(JsonNode position) {
        ObjectNode target = Json.object();
        for (String field : List.of("x", "y", "z")) {
            if (!position.path(field).isIntegralNumber() || !position.path(field).canConvertToInt()) {
                throw new IllegalArgumentException("position." + field + " must be an integer");
            }
            target.put(field, position.path(field).asInt());
        }
        int x = target.path("x").asInt(), y = target.path("y").asInt(), z = target.path("z").asInt();
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || y < -2048 || y > 2048) {
            throw new IllegalArgumentException("position is outside safe world bounds");
        }
        target.put("dimension", position.path("dimension").asText("minecraft:overworld"));
        return target;
    }

    private static String enumValue(String value, String label, Set<String> allowed) {
        if (!allowed.contains(value)) throw new IllegalArgumentException(label + " is invalid");
        return value;
    }

    private static String namespacedId(String value, String label) {
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException(label + " must be a namespaced identifier");
        }
        return value;
    }

    private Intent step(ToolContext context, JsonNode arguments) throws java.sql.SQLException {
        rejectUnexpected(arguments, Set.of("dx", "dy", "dz"));
        int dx = boundedDelta(arguments, "dx");
        int dy = boundedDelta(arguments, "dy");
        int dz = boundedDelta(arguments, "dz");
        if (dx == 0 && dy == 0 && dz == 0) throw new IllegalArgumentException("step delta must not be zero");
        JsonNode status = companions.get(context.companionId())
                .orElseThrow(() -> new IllegalArgumentException("COMPANION_NOT_FOUND")).status();
        JsonNode position = status.path("position");
        for (String field : List.of("x", "y", "z")) {
            if (!position.path(field).canConvertToInt()) {
                throw new IllegalArgumentException("connected-body position is unavailable");
            }
        }
        long x = (long) position.path("x").asInt() + dx;
        long y = (long) position.path("y").asInt() + dy;
        long z = (long) position.path("z").asInt() + dz;
        if (Math.abs(x) > 30_000_000L || Math.abs(z) > 30_000_000L || y < -2048L || y > 2048L) {
            throw new IllegalArgumentException("relative target is outside safe bounds");
        }
        ObjectNode target = Json.object().put("dimension", status.path("dimension").asText("minecraft:overworld"))
                .put("x", x).put("y", y).put("z", z);
        return new Intent(TaskType.TRAVEL, Json.object().set("target", target), "movement.step");
    }

    private Intent movementStop(ToolContext context, JsonNode arguments) throws java.sql.SQLException {
        rejectUnexpected(arguments, Set.of());
        if (tasks == null) throw new IllegalArgumentException("durable task state is unavailable");
        TaskRecord active = tasks.activeForCompanion(context.companionId())
                .orElseThrow(() -> new IllegalArgumentException("no active movement task"));
        if (active.type() != TaskType.TRAVEL && active.type() != TaskType.FOLLOW
                && active.type() != TaskType.RETURN) {
            throw new IllegalArgumentException("active task is not a movement task");
        }
        return stop("cancel");
    }

    private static int boundedDelta(JsonNode arguments, String field) {
        JsonNode value = arguments.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int delta = value.asInt();
        if (delta < -8 || delta > 8) throw new IllegalArgumentException(field + " must be -8..8");
        return delta;
    }

    private static Intent breakBlock(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("block", "position"));
        ObjectNode mine = Json.object().put("block", arguments.path("block").asText())
                .put("maxBlocks", 1).put("allowPartial", false);
        mine.set("origin", arguments.path("position"));
        return skill("MineResourceVein", validatedMine(mine));
    }

    private static Intent transfer(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("direction", "item", "quantity", "allowPartial", "container"));
        String direction = arguments.path("direction").asText("");
        if (!direction.equals("FROM_CONTAINER") && !direction.equals("TO_CONTAINER")) {
            throw new IllegalArgumentException("direction is invalid");
        }
        ObjectNode values = (ObjectNode) arguments.deepCopy();
        values.remove("direction");
        return skill(direction.equals("FROM_CONTAINER") ? "WithdrawFromStorage" : "DepositToStorage",
                validatedWithdraw(values));
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
        } else if (name.equals("movement.look")) {
            root.putArray("required").add("x").add("y").add("z");
        } else if (name.equals("movement.step")) {
            root.putArray("required").add("dx").add("dy").add("dz");
        } else if (name.equals("block.break")) {
            root.putArray("required").add("block").add("position");
        } else if (name.equals("block.interact")) {
            root.putArray("required").add("position");
        } else if (name.equals("block.place")) {
            root.putArray("required").add("block").add("position");
        } else if (name.equals("entity.interact") || name.equals("entity.attack")
                || name.equals("safety.retreat")) {
            root.putArray("required").add("entityId");
        } else if (name.equals("menu.click")) {
            root.putArray("required").add("sessionToken").add("slot").add("button");
        } else if (name.equals("menu.quick_move")) {
            root.putArray("required").add("sessionToken").add("slot");
        } else if (name.equals("menu.close")) {
            root.putArray("required").add("sessionToken");
        } else if (name.equals("item.use")) {
            root.putArray("required").add("item");
        } else if (name.equals("inventory.withdraw") || name.equals("inventory.deposit")) {
            root.putArray("required").add("item").add("quantity").add("container");
        } else if (name.equals("inventory.transfer")) {
            root.putArray("required").add("direction").add("item").add("quantity").add("container");
        } else if (name.equals("inventory.deliver") || name.equals("inventory.drop")
                || name.equals("item.craft")
                || name.equals("resource.collect") || name.equals("entity.collect")) {
            root.putArray("required").add("item").add("quantity");
        } else if (name.equals("world.scan")) {
            root.putArray("required").add("block").add("radius");
        } else if (name.equals("resource.mine_vein")) {
            root.putArray("required").add("block").add("maxBlocks").add("origin");
        } else if (name.equals("item.smelt")) {
            root.putArray("required").add("item").add("quantity").add("station");
        } else if (name.equals("task_graph.validate") || name.equals("task_graph.execute")) {
            var alternatives = root.putArray("oneOf");
            alternatives.addObject().putArray("required").add("graph");
            alternatives.addObject().putArray("required").add("document").add("format");
        } else if (name.equals("task_graph.inspect") || name.equals("task_graph.pause")
                || name.equals("task_graph.resume") || name.equals("task_graph.cancel")) {
            root.putArray("required").add("executionId");
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

    private static ObjectNode stepSchema() {
        ObjectNode properties = Json.object();
        for (String field : List.of("dx", "dy", "dz")) {
            properties.putObject(field).put("type", "integer").put("minimum", -8).put("maximum", 8);
        }
        return properties;
    }

    private static ObjectNode lookSchema() {
        return coordinateSchema();
    }

    private static ObjectNode blockBreakSchema() {
        ObjectNode properties = Json.object();
        properties.putObject("block").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        ObjectNode position = properties.putObject("position");
        position.put("type", "object").put("additionalProperties", false);
        ObjectNode fields = position.putObject("properties");
        fields.putObject("dimension").put("type", "string");
        for (String field : List.of("x", "y", "z")) fields.putObject(field).put("type", "integer");
        position.putArray("required").add("x").add("y").add("z");
        return properties;
    }

    private static ObjectNode blockInteractionSchema() {
        ObjectNode properties = Json.object();
        ObjectNode position = properties.putObject("position");
        position.put("type", "object").put("additionalProperties", false);
        ObjectNode fields = position.putObject("properties");
        fields.putObject("dimension").put("type", "string");
        for (String field : List.of("x", "y", "z")) fields.putObject(field).put("type", "integer");
        position.putArray("required").add("x").add("y").add("z");
        properties.putObject("face").put("type", "string").putArray("enum")
                .add("DOWN").add("UP").add("NORTH").add("SOUTH").add("WEST").add("EAST");
        properties.putObject("hand").put("type", "string").putArray("enum")
                .add("MAIN_HAND").add("OFF_HAND");
        return properties;
    }

    private static ObjectNode blockPlaceSchema() {
        ObjectNode properties = blockInteractionSchema();
        properties.putObject("block").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        return properties;
    }

    private static ObjectNode entityInteractionSchema() {
        ObjectNode properties = Json.object();
        properties.putObject("entityId").put("type", "string")
                .put("pattern", "^[0-9a-fA-F-]{36}$");
        properties.putObject("hand").put("type", "string").putArray("enum")
                .add("MAIN_HAND").add("OFF_HAND");
        return properties;
    }

    private static ObjectNode entityAttackSchema() {
        ObjectNode properties = Json.object();
        properties.putObject("entityId").put("type", "string")
                .put("pattern", "^[0-9a-fA-F-]{36}$");
        return properties;
    }

    private static ObjectNode menuClickSchema() {
        ObjectNode properties = menuSlotSchema();
        properties.putObject("button").put("type", "integer").put("minimum", 0).put("maximum", 1);
        return properties;
    }

    private static ObjectNode menuSlotSchema() {
        ObjectNode properties = menuCloseSchema();
        properties.putObject("slot").put("type", "integer").put("minimum", 0).put("maximum", 127);
        return properties;
    }

    private static ObjectNode menuCloseSchema() {
        ObjectNode properties = Json.object();
        properties.putObject("sessionToken").put("type", "string")
                .put("pattern", "^[A-Za-z0-9_-]{32}$");
        return properties;
    }

    private static ObjectNode itemUseSchema() {
        ObjectNode properties = Json.object();
        properties.putObject("item").put("type", "string")
                .put("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$");
        properties.putObject("hand").put("type", "string").putArray("enum")
                .add("MAIN_HAND").add("OFF_HAND");
        properties.putObject("durationTicks").put("type", "integer")
                .put("minimum", 0).put("maximum", 720);
        return properties;
    }

    private static ObjectNode dropSchema() {
        ObjectNode properties = itemQuantitySchema();
        ((ObjectNode) properties.path("quantity")).put("maximum", 64);
        return properties;
    }

    private static ObjectNode transferSchema() {
        ObjectNode properties = withdrawSchema();
        properties.putObject("direction").put("type", "string").putArray("enum")
                .add("FROM_CONTAINER").add("TO_CONTAINER");
        return properties;
    }

    private static ObjectNode taskGraphSchema() {
        ObjectNode properties = Json.object();
        properties.putObject("graph").put("type", "object");
        properties.putObject("document").put("type", "string").put("maxLength", 2 * 1024 * 1024);
        properties.putObject("format").put("type", "string").putArray("enum").add("json").add("yaml");
        properties.putObject("inputs").put("type", "object");
        properties.putObject("provenance").put("type", "object");
        return properties;
    }

    private static JsonNode parsedTaskGraph(JsonNode arguments) {
        rejectUnexpected(arguments, Set.of("graph", "document", "format", "inputs", "provenance"));
        boolean hasGraph = arguments.has("graph");
        boolean hasDocument = arguments.has("document") || arguments.has("format");
        if (hasGraph == hasDocument) {
            throw new IllegalArgumentException("provide exactly one of graph or document+format");
        }
        if (hasGraph) {
            JsonNode graph = arguments.path("graph");
            if (!graph.isObject()) throw new IllegalArgumentException("graph must be an object");
            return graph;
        }
        if (!arguments.path("document").isTextual() || !arguments.path("format").isTextual()) {
            throw new IllegalArgumentException("document and format must be strings");
        }
        TaskGraphCodec.Format format = switch (arguments.path("format").asText()) {
            case "json" -> TaskGraphCodec.Format.JSON;
            case "yaml" -> TaskGraphCodec.Format.YAML;
            default -> throw new IllegalArgumentException("format must be json or yaml");
        };
        return TaskGraphCodec.parse(arguments.path("document").asText(), format);
    }

    private static ObjectNode executionIdSchema() {
        ObjectNode properties = Json.object();
        properties.putObject("executionId").put("type", "string").put("minLength", 1).put("maxLength", 256);
        return properties;
    }

    @Override public void close() {
        if (taskGraphRuntime != null) taskGraphRuntime.close();
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
