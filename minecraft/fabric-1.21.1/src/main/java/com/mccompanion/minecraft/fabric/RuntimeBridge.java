package com.mccompanion.minecraft.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.minecraft.v121.CompanionRegistry;
import com.mccompanion.minecraft.v121.SkillParameters;
import com.mccompanion.minecraft.bridge.ConversationDeliveryWindow;
import com.mccompanion.minecraft.bridge.RuntimeCommandArguments;
import com.mccompanion.minecraft.bridge.ConnectionEpochGate;
import com.mccompanion.minecraft.bridge.EntityEventTracker;
import com.mccompanion.minecraft.bridge.InventoryWorldEventTracker;
import com.mccompanion.minecraft.bridge.SurvivalEventTracker;
import com.mccompanion.minecraft.v121.EntityEventObservationService;
import com.mccompanion.minecraft.v121.InventoryWorldEventObservationService;
import com.mccompanion.minecraft.v121.SurvivalEventObservationService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import com.mccompanion.minecraft.v121.CompanionCommands;
import org.slf4j.Logger;

/** Optional, reconnecting local WebSocket bridge. All game mutations are re-entered on the server thread. */
final class RuntimeBridge implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROTOCOL = "mc-companion/1";
    private static final int MAX_PENDING_PLAYER_REQUESTS = 128;
    private static final long PLAYER_REQUEST_TTL_MILLIS = 30_000;

    private final MinecraftServer server;
    private final CompanionRegistry registry;
    private final Logger logger;
    private final URI uri;
    private final boolean enabled;
    private final Path tokenFile;
    private final String installationId;
    private final String instanceId;
    private final String launcherType;
    private final ScheduledExecutorService executor;
    private final HttpClient client;
    private final AtomicLong outgoingSequence = new AtomicLong();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final ConnectionEpochGate<WebSocket> connections = new ConnectionEpochGate<>();
    private final Map<String, String> observedBehaviorStates = new ConcurrentHashMap<>();
    private final Map<String, UUID> pendingPlayerRequests = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingPlayerRequestTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerRequestTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> ownerActivityTimes = new ConcurrentHashMap<>();
    private final ConversationDeliveryWindow deliveredConversationEvents =
            new ConversationDeliveryWindow(512);
    private final EntityEventTracker entityEvents = new EntityEventTracker();
    private final EntityEventObservationService entityEventObservations;
    private final SurvivalEventTracker survivalEvents = new SurvivalEventTracker();
    private final SurvivalEventObservationService survivalEventObservations;
    private final InventoryWorldEventTracker inventoryWorldEvents = new InventoryWorldEventTracker();
    private final InventoryWorldEventObservationService inventoryWorldEventObservations;
    private volatile WebSocket socket;
    private volatile String sessionId;
    private volatile boolean closed;
    private volatile boolean missingTokenReported;

    private RuntimeBridge(MinecraftServer server, CompanionRegistry registry, Logger logger) {
        this.server = server;
        this.registry = registry;
        this.logger = logger;
        BridgeSettings settings = BridgeSettings.load(logger);
        this.enabled = settings.enabled();
        this.uri = settings.uri();
        this.tokenFile = settings.tokenFile();
        this.installationId = settings.installationId();
        this.instanceId = settings.instanceId();
        this.launcherType = settings.launcherType();
        this.entityEventObservations = new EntityEventObservationService(server);
        this.survivalEventObservations = new SurvivalEventObservationService();
        this.inventoryWorldEventObservations = new InventoryWorldEventObservationService();
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mc-companion-runtime-bridge");
            thread.setDaemon(true);
            return thread;
        });
        this.client = HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofSeconds(3)).build();
    }

    static RuntimeBridge start(MinecraftServer server, CompanionRegistry registry, Logger logger) {
        RuntimeBridge bridge = new RuntimeBridge(server, registry, logger);
        if (!bridge.enabled) {
            logger.info("Runtime bridge disabled by instance config; local companion control remains available");
            return bridge;
        }
        bridge.executor.scheduleWithFixedDelay(bridge::connectIfNeeded, 0, 5, TimeUnit.SECONDS);
        bridge.executor.scheduleWithFixedDelay(bridge::publishStatus, 1, 1, TimeUnit.SECONDS);
        return bridge;
    }

    private void connectIfNeeded() {
        if (closed || socket != null || !connecting.compareAndSet(false, true)) return;
        try {
            if (!Files.isRegularFile(tokenFile)) {
                if (!missingTokenReported) {
                    logger.info("Runtime bridge offline: pairing token not found at {}", tokenFile);
                    missingTokenReported = true;
                }
                connecting.set(false);
                return;
            }
            String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            if (token.length() < 16 || token.length() > 512 || token.chars().anyMatch(Character::isWhitespace)) {
                logger.warn("Runtime bridge token file is invalid; expected one 16..512 character token");
                connecting.set(false);
                return;
            }
            missingTokenReported = false;
            long attempt = connections.beginAttempt();
            client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + token)
                    .buildAsync(uri, new Listener(attempt))
                    .whenComplete((webSocket, failure) -> {
                        connecting.set(false);
                        if (failure != null && !closed) {
                            logger.warn("Runtime bridge connection failed; local commands remain available ({})",
                                    failure.getClass().getSimpleName());
                        }
                    });
        } catch (IOException | RuntimeException failure) {
            connecting.set(false);
            logger.warn("Runtime bridge could not read its local configuration ({})",
                    failure.getClass().getSimpleName());
        }
    }

    private void sendHello(WebSocket webSocket) {
        ObjectNode payload = JSON.createObjectNode()
                .put("protocol", PROTOCOL)
                .put("modVersion", MinecraftAiCompanionFabric.MOD_VERSION)
                .put("minecraftVersion", "1.21.1")
                .put("loader", "fabric")
                .put("worldId", worldId());
        if (installationId != null) payload.put("installationId", installationId);
        if (instanceId != null) payload.put("instanceId", instanceId);
        if (launcherType != null) payload.put("launcherType", launcherType);
        payload.putObject("capabilities")
                .put("server_player_body", true)
                .put("follow", true)
                .put("travel", true)
                .put("bounded_world_snapshot", true)
                .put("inventory_observation", true)
                .put("registry_query", true)
                .put("recipe_query", true)
                .put("primitive_observation_query", true)
                .put("primitive_lifecycle", true)
                .put("player_entity_events", true)
                .put("survival_events", true)
                .put("inventory_world_events", true)
                .put("LookAt", true)
                .put("InteractBlock", true)
                .put("InteractEntity", true)
                .put("MenuAction", true)
                .put("UseItem", true)
                .put("DropItem", true)
                .put("AttackEntity", true)
                .put("PlaceBlock", true)
                .put("RetreatFromDanger", true)
                .put("NavigateTo", true)
                .put("NavigateWithWorldChanges", true)
                .put("FollowOwner", true)
                .put("WithdrawFromStorage", true)
                .put("DepositToStorage", true)
                .put("CraftItem", true)
                .put("ExploreArea", true)
                .put("CollectResource", true)
                .put("MineResourceVein", true)
                .put("SmeltItem", true)
                .put("DefendOwner", true)
                .put("DeliverItem", true)
                .put("EatAndRecover", true)
                .put("EquipItem", true)
                .put("SleepAtBed", true)
                .put("UseWaterBucket", true)
                .put("UseVehicle", true)
                .put("Fish", true)
                .put("FarmCrop", true)
                .put("BreedAnimals", true)
                .put("TradeWithVillager", true)
                .put("EnchantItem", true)
                .put("BrewPotion", true)
                .put("GlideWithElytra", true)
                .put("runtime_safe_idle", true);
        ObjectNode hello = JSON.createObjectNode().put("protocol", PROTOCOL).put("type", "hello");
        hello.set("payload", payload);
        webSocket.sendText(write(hello), true);
    }

    private void handle(long attempt, WebSocket source, String text) {
        if (!connections.isCurrent(attempt, source)) return;
        final JsonNode message;
        try {
            message = JSON.readTree(text);
        } catch (IOException malformed) {
            logger.warn("Runtime bridge ignored malformed JSON");
            return;
        }
        String type = message.path("type").asText("");
        if (!connections.isCurrent(attempt, source)) return;
        if (type.equals("hello_ack")) {
            if (!message.path("accepted").asBoolean(false)) {
                logger.warn("Runtime rejected bridge handshake: {}", message.path("code").asText("UNKNOWN"));
                closeSocket(1008, "handshake rejected");
                return;
            }
            sessionId = message.path("sessionId").asText();
            outgoingSequence.set(0);
            server.execute(() -> {
                if (connections.isCurrent(attempt, source)) registry.setRuntimeConnected(true);
            });
            logger.info("Runtime bridge connected: protocol={} safeIdleOnDisconnect=true", PROTOCOL);
            publishStatus();
            return;
        }
        if (type.equals("query") && message.path("name").asText().equals("list_companions")) {
            publishStatus();
        } else if (type.equals("command")) {
            server.execute(() -> {
                if (connections.isCurrent(attempt, source)) processCommand(message.path("payload"));
            });
        } else if (type.equals("player_reply")) {
            deliverPlayerReply(message.path("payload"));
        } else if (type.equals("conversation_event")) {
            deliverConversationEvent(message.path("payload"));
        } else if (type.equals("heartbeat_ack") || type.equals("subscription")) {
            // Subscription is fulfilled by the periodic status publisher.
        }
    }

    CompanionCommands.TextRequestResult submitPlayerText(ServerPlayer owner, String text) {
        if (socket == null || sessionId == null) return new CompanionCommands.TextRequestResult(false,
                "Runtime 未连接；复杂任务不会被静默猜测执行。");
        long now = System.currentTimeMillis();
        cleanupTransientState(now);
        if (pendingPlayerRequests.size() >= MAX_PENDING_PLAYER_REQUESTS) {
            return new CompanionCommands.TextRequestResult(false, "待处理请求已满，请稍后重试。");
        }
        Long previous = playerRequestTimes.put(owner.getUUID(), now);
        if (previous != null && now - previous < 1500) return new CompanionCommands.TextRequestResult(false, "请求过快，请稍等片刻。");
        String companionId = registry.runtimeSnapshots(true).stream()
                .filter(value -> value.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId).findFirst().orElse(null);
        if (companionId == null) return new CompanionCommands.TextRequestResult(false, "你还没有可用的 Companion。");
        String requestId = UUID.randomUUID().toString();
        pendingPlayerRequests.put(requestId, owner.getUUID());
        pendingPlayerRequestTimes.put(requestId, now);
        ObjectNode payload = JSON.createObjectNode().put("requestId", requestId).put("companionId", companionId)
                .put("ownerId", owner.getUUID().toString()).put("text", text);
        sendEnvelope("player_request", payload);
        return new CompanionCommands.TextRequestResult(true, "收到，我先结合当前世界状态理解这个目标。");
    }

    void submitOwnerBlockActivity(ServerPlayer owner, BlockPos position, String activityType) {
        if (socket == null || sessionId == null || owner == null || position == null) return;
        String companionId = registry.runtimeSnapshots(true).stream()
                .filter(value -> value.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId).findFirst().orElse(null);
        if (companionId == null) return;
        String dimension = owner.serverLevel().dimension().location().toString();
        String key = owner.getUUID() + ":" + activityType + ":" + dimension + ":"
                + position.getX() + ":" + position.getY() + ":" + position.getZ();
        long now = System.currentTimeMillis();
        Long previous = ownerActivityTimes.put(key, now);
        if (previous != null && now - previous < 250) return;
        while (ownerActivityTimes.size() > 128) {
            String oldest = ownerActivityTimes.keySet().iterator().next();
            ownerActivityTimes.remove(oldest);
        }
        ObjectNode payload = JSON.createObjectNode()
                .put("companionId", companionId)
                .put("ownerId", owner.getUUID().toString())
                .put("activityType", activityType);
        payload.putObject("position").put("dimension", dimension)
                .put("x", position.getX()).put("y", position.getY()).put("z", position.getZ());
        sendEnvelope("owner_activity", payload);
    }

    private void deliverPlayerReply(JsonNode payload) {
        String requestId = payload.path("requestId").asText("");
        UUID ownerId = pendingPlayerRequests.remove(requestId);
        pendingPlayerRequestTimes.remove(requestId);
        if (ownerId == null) return;
        String reply = payload.path("reply").asText("请求已处理。");
        if (reply.length() > 512) reply = reply.substring(0, 512);
        String finalReply = reply;
        server.execute(() -> {
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            if (owner != null) owner.sendSystemMessage(
                    Component.translatable("mcac.chat.prefix").append(Component.literal(finalReply)));
        });
    }

    private void deliverConversationEvent(JsonNode payload) {
        String companionId = payload.path("companionId").asText("");
        String eventId = payload.path("eventId").asText("");
        String reply = payload.path("reply").asText("").strip();
        if (eventId.isEmpty() || reply.isEmpty()) return;
        if (reply.length() > 512) reply = reply.substring(0, 512);
        String finalReply = reply;
        server.execute(() -> registry.runtimeSnapshots(true).stream()
                .filter(value -> value.companionId().equals(companionId)).findFirst().ifPresent(snapshot -> {
                    try {
                        ServerPlayer owner =
                                server.getPlayerList().getPlayer(UUID.fromString(snapshot.ownerId()));
                        if (owner != null) {
                            if (!deliveredConversationEvents.firstDelivery(eventId)) {
                                sendConversationDeliveryAck(eventId, companionId);
                                return;
                            }
                            owner.sendSystemMessage(Component.translatable("mcac.chat.prefix")
                                    .append(Component.literal(finalReply)));
                            logger.info("conversation_delivered_to_game event={} kind={} companion={}",
                                    payload.path("eventId").asText("unknown"),
                                    payload.path("kind").asText("MESSAGE"), companionId);
                            sendConversationDeliveryAck(eventId, companionId);
                        }
                    } catch (IllegalArgumentException ignored) {
                        logger.warn("Runtime sent an invalid owner identity for companion {}", companionId);
                    }
                }));
    }

    private void sendConversationDeliveryAck(String eventId, String companionId) {
        sendEnvelope("conversation_delivery_ack", JSON.createObjectNode()
                .put("eventId", eventId).put("companionId", companionId));
    }

    private void processCommand(JsonNode command) {
        String commandId = command.path("commandId").asText();
        String companionId = command.path("companionId").asText();
        String commandType = command.path("command").asText().toUpperCase(Locale.ROOT).replace('-', '_');
        String leaseId = command.path("leaseId").isNull() ? null : command.path("leaseId").asText(null);
        long epoch = command.path("controlEpoch").asLong(0);
        JsonNode arguments = command.path("arguments");
        CompanionRegistry.RuntimeResult result;
        switch (commandType) {
            case "ACQUIRE_LEASE" -> result = registry.runtimeAcquireLease(
                    companionId,
                    arguments.path("proposedLeaseId").asText(),
                    arguments.path("proposedEpoch").asLong(),
                    arguments.path("expiresAt").asLong());
            case "RENEW_LEASE" -> result = registry.runtimeRenewLease(
                    companionId, leaseId, epoch, arguments.path("expiresAt").asLong());
            case "RELEASE_LEASE" -> result = registry.runtimeReleaseLease(companionId, leaseId, epoch);
            case "START_BEHAVIOR" -> {
                JsonNode parameters = RuntimeCommandArguments.skillParameters(arguments::path);
                JsonNode target = parameters.path("target");
                result = registry.runtimeStart(
                        companionId, leaseId, epoch,
                        arguments.path("behaviorId").asText(),
                        arguments.path("behaviorType").asText(),
                        target.has("x") ? target.path("x").asDouble() : null,
                        target.has("y") ? target.path("y").asDouble() : null,
                        target.has("z") ? target.path("z").asDouble() : null,
                        skillParameters(parameters));
            }
            case "PAUSE_BEHAVIOR" -> result = registry.runtimePause(companionId, leaseId, epoch);
            case "RESUME_BEHAVIOR" -> result = registry.runtimeResume(companionId, leaseId, epoch);
            case "CANCEL_BEHAVIOR" -> result = registry.runtimeCancel(companionId, leaseId, epoch);
            case "QUERY_STATUS" -> {
                publishStatusOnServerThread();
                result = new CompanionRegistry.RuntimeResult(true, "OK", null, 0, "STATUS");
            }
            case "QUERY_REGISTRY" -> {
                sendQueryResult(commandId, companionId, arguments,
                        RegistryObservationService.registry(server, arguments));
                result = new CompanionRegistry.RuntimeResult(true, "OK", null, 0, "STATUS");
            }
            case "QUERY_RECIPE" -> {
                sendQueryResult(commandId, companionId, arguments,
                        RegistryObservationService.recipes(server, arguments));
                result = new CompanionRegistry.RuntimeResult(true, "OK", null, 0, "STATUS");
            }
            case "QUERY_OBSERVATION" -> {
                sendObservationResult(commandId, companionId, arguments,
                        PrimitiveObservationService.inspect(registry, companionId, arguments));
                result = new CompanionRegistry.RuntimeResult(true, "OK", null, 0, "STATUS");
            }
            default -> result = new CompanionRegistry.RuntimeResult(false, "UNKNOWN_COMMAND", null, 0, "FAILED");
        }
        if (!result.success()) {
            sendProtocolError(commandId, result.code());
            return;
        }
        registry.recordRuntimeCommand();
        sendCommandAccepted(commandId, result);
        if (result.behaviorId() != null && !commandType.equals("ACQUIRE_LEASE")
                && !commandType.equals("RENEW_LEASE") && !commandType.equals("RELEASE_LEASE")) {
            sendBehaviorEvent(commandId, companionId, result, commandType);
            observedBehaviorStates.put(companionId + ':' + result.behaviorId(), result.state().toUpperCase(Locale.ROOT));
        }
        publishStatusOnServerThread();
    }

    private void sendQueryResult(String commandId, String companionId, JsonNode arguments,
                                 RegistryObservationService.Result result) {
        ObjectNode payload = JSON.createObjectNode()
                .put("queryId", arguments.path("queryId").asText())
                .put("commandId", commandId)
                .put("companionId", companionId)
                .put("success", result.success())
                .put("code", result.code());
        payload.set("observation", result.observation());
        sendEnvelope("registry_result", payload);
    }

    private void sendObservationResult(String commandId, String companionId, JsonNode arguments,
                                       PrimitiveObservationService.Result result) {
        ObjectNode payload = JSON.createObjectNode()
                .put("queryId", arguments.path("queryId").asText())
                .put("commandId", commandId)
                .put("companionId", companionId)
                .put("success", result.success())
                .put("code", result.code());
        payload.set("observation", result.observation());
        sendEnvelope("observation_result", payload);
    }

    private static SkillParameters skillParameters(JsonNode parameters) {
        if (!parameters.path("capability").isTextual()) return null;
        JsonNode values = parameters.path("parameters");
        String item = values.path("item").asText(values.path("itemId").asText(""));
        int quantity = values.path("quantity").asInt(1);
        JsonNode target = values.path("container").isObject() ? values.path("container")
                : values.path("station").isObject() ? values.path("station") : values.path("target");
        Integer x = target.path("x").canConvertToInt() ? target.path("x").asInt() : null;
        Integer y = target.path("y").canConvertToInt() ? target.path("y").asInt() : null;
        Integer z = target.path("z").canConvertToInt() ? target.path("z").asInt() : null;
        try { return new SkillParameters(parameters.path("capability").asText(), item, quantity,
                values.path("allowPartial").asBoolean(false),
                target.path("dimension").asText("minecraft:overworld"), x, y, z,
                values.path("entityId").asText(""), values.path("face").asText("UP"),
                values.path("hand").asText("MAIN_HAND"),
                values.path("sessionToken").asText(""),
                values.path("slot").canConvertToInt() ? values.path("slot").asInt() : null,
                values.path("button").canConvertToInt() ? values.path("button").asInt() : null,
                values.path("action").asText(""),
                values.path("durationTicks").canConvertToInt()
                        ? values.path("durationTicks").asInt() : null,
                values.path("partnerEntityId").asText(""),
                stringList(values.path("allowedBreakBlocks")),
                stringList(values.path("allowedPlaceBlocks")),
                values.path("maxBreakBlocks").asInt(0),
                values.path("maxPlaceBlocks").asInt(0),
                values.path("maxRiskUnits").asInt(8),
                values.path("targetReferenceKind").asText(""),
                values.path("targetName").asText(""),
                values.path("targetRuntimeId").canConvertToInt() ? values.path("targetRuntimeId").asInt() : null,
                optionalDouble(values.path("minimumDistance")),
                optionalDouble(values.path("maximumDistance")),
                values.path("lostTimeoutTicks").canConvertToInt() ? values.path("lostTimeoutTicks").asInt() : null); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static java.util.List<String> stringList(JsonNode value) {
        if (!value.isArray()) return java.util.List.of();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        value.forEach(entry -> { if (entry.isTextual()) result.add(entry.asText()); });
        return java.util.List.copyOf(result);
    }

    private static Double optionalDouble(JsonNode value) {
        return value.isNumber() ? value.asDouble() : null;
    }

    private void sendCommandAccepted(String commandId, CompanionRegistry.RuntimeResult result) {
        ObjectNode payload = JSON.createObjectNode()
                .put("commandId", commandId)
                .put("duplicate", false)
                .put("behaviorRevision", result.behaviorRevision())
                .put("acceptedAt", Instant.now().toString());
        if (result.behaviorId() != null) payload.put("behaviorId", result.behaviorId());
        sendEnvelope("command_accepted", payload);
    }

    private void sendBehaviorEvent(
            String commandId,
            String companionId,
            CompanionRegistry.RuntimeResult result,
            String commandType) {
        String event = switch (commandType) {
            case "PAUSE_BEHAVIOR" -> "paused";
            case "RESUME_BEHAVIOR" -> "resumed";
            case "CANCEL_BEHAVIOR", "RELEASE_LEASE" -> "cancelled";
            default -> "started";
        };
        String state = result.state().toLowerCase(Locale.ROOT);
        ObjectNode payload = JSON.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("behaviorId", result.behaviorId())
                .put("commandId", commandId)
                .put("companionId", companionId)
                .put("event", event)
                .put("state", state)
                .put("revision", result.behaviorRevision())
                .put("tick", server.getTickCount())
                .put("progress", 0.0D)
                .put("occurredAt", Instant.now().toString());
        ObjectNode eventSnapshot = payload.putObject("snapshot").put("controlEpoch", currentEpoch(companionId));
        registry.runtimeSnapshots(true).stream()
                .filter(value -> value.companionId().equals(companionId)
                        && (value.behaviorId() == null || result.behaviorId().equals(value.behaviorId())))
                .findFirst()
                .ifPresent(value -> {
                    appendRuntimeSnapshot(eventSnapshot, value);
                    appendBehaviorObservation(eventSnapshot, value.behaviorObservation());
                });
        sendEnvelope("behavior_event", payload);
    }

    private long currentEpoch(String companionId) {
        return registry.runtimeSnapshots(true).stream()
                .filter(value -> value.companionId().equals(companionId))
                .mapToLong(CompanionRegistry.RuntimeSnapshot::controlEpoch)
                .findFirst().orElse(0L);
    }

    private void sendProtocolError(String commandId, String code) {
        ObjectNode payload = JSON.createObjectNode()
                .put("failureCode", code)
                .put("message", "Mod rejected Runtime command: " + code)
                .put("retryable", code.equals("RUNTIME_OFFLINE") || code.equals("OWNER_OFFLINE"))
                .put("commandId", commandId)
                .put("occurredAt", Instant.now().toString());
        payload.putObject("details");
        sendEnvelope("protocol_error", payload);
    }

    private void publishStatus() {
        cleanupTransientState(System.currentTimeMillis());
        if (closed || socket == null || sessionId == null) return;
        server.execute(this::publishStatusOnServerThread);
    }

    /** Runs on the Minecraft server thread and observes only bounded areas around live bodies. */
    void tick() {
        if (closed || socket == null || sessionId == null || server.getTickCount() % 5 != 0) return;
        java.util.Set<String> observedCompanions = new java.util.HashSet<>();
        Instant observedAt = Instant.now();
        for (CompanionRegistry.EntityEventBinding binding : registry.entityEventBindings()) {
            observedCompanions.add(binding.companionId());
            EntityEventTracker.Snapshot snapshot = entityEventObservations.snapshot(
                    binding.body(), binding.behaviorId(), binding.target(), server.getTickCount(), observedAt);
            for (EntityEventTracker.Event event : entityEvents.observe(snapshot)) sendEntityEvent(event);
        }
        entityEvents.retainCompanions(observedCompanions);
        java.util.Set<String> observedSurvivalCompanions = new java.util.HashSet<>();
        for (CompanionRegistry.SurvivalEventBinding binding : registry.survivalEventBindings()) {
            observedSurvivalCompanions.add(binding.companionId());
            SurvivalEventTracker.Snapshot snapshot = survivalEventObservations.snapshot(
                    binding, server.getTickCount(), observedAt);
            for (SurvivalEventTracker.Event event : survivalEvents.observe(snapshot)) {
                sendSurvivalEvent(event);
            }
        }
        survivalEvents.retainCompanions(observedSurvivalCompanions);
        java.util.Set<String> observedInventoryWorldCompanions = new java.util.HashSet<>();
        for (CompanionRegistry.InventoryWorldEventBinding binding : registry.inventoryWorldEventBindings()) {
            observedInventoryWorldCompanions.add(binding.companionId());
            InventoryWorldEventTracker.Snapshot snapshot = inventoryWorldEventObservations.snapshot(
                    binding, server.getTickCount(), observedAt);
            for (InventoryWorldEventTracker.Event event : inventoryWorldEvents.observe(snapshot)) {
                sendInventoryWorldEvent(event);
            }
        }
        inventoryWorldEvents.retainCompanions(observedInventoryWorldCompanions);
    }

    private void sendEntityEvent(EntityEventTracker.Event event) {
        ObjectNode payload = JSON.createObjectNode()
                .put("eventId", event.eventId())
                .put("eventType", event.type().name())
                .put("priority", event.priority().name())
                .put("source", "MINECRAFT_ENTITY_OBSERVER")
                .put("companionId", event.companionId())
                .put("tick", event.tick())
                .put("occurredAt", event.occurredAt().toString());
        if (event.behaviorId() != null) payload.put("behaviorId", event.behaviorId());
        EntityEventTracker.EntityFact target = event.target();
        payload.putObject("target")
                .put("entityId", target.identity()).put("entityType", target.type())
                .put("displayName", target.displayName() == null ? "" : target.displayName())
                .put("player", target.player()).put("hostile", target.hostile())
                .put("alive", target.alive()).put("distanceSquared", target.distanceSquared());
        sendEnvelope("player_entity_event", payload);
    }

    private void sendSurvivalEvent(SurvivalEventTracker.Event event) {
        SurvivalEventTracker.Snapshot snapshot = event.snapshot();
        ObjectNode payload = JSON.createObjectNode()
                .put("eventId", event.eventId()).put("eventType", event.type().name())
                .put("priority", event.priority().name()).put("source", "MINECRAFT_SURVIVAL_OBSERVER")
                .put("companionId", snapshot.companionId()).put("tick", snapshot.tick())
                .put("occurredAt", snapshot.observedAt().toString())
                .put("previousHealth", event.previousHealth()).put("damageAmount", event.damageAmount());
        if (snapshot.behaviorId() != null) payload.put("behaviorId", snapshot.behaviorId());
        payload.putObject("vitals")
                .put("lifecycle", snapshot.lifecycle().name())
                .put("health", snapshot.health()).put("maxHealth", snapshot.maxHealth())
                .put("air", snapshot.air()).put("maxAir", snapshot.maxAir())
                .put("onFire", snapshot.onFire()).put("inLava", snapshot.inLava())
                .put("onGround", snapshot.onGround()).put("fallDistance", snapshot.fallDistance());
        sendEnvelope("survival_event", payload);
    }

    private void sendInventoryWorldEvent(InventoryWorldEventTracker.Event event) {
        InventoryWorldEventTracker.Snapshot snapshot = event.snapshot();
        boolean inventoryCategory = event.type().ordinal()
                <= InventoryWorldEventTracker.Type.RESOURCE_TARGET_REACHED.ordinal();
        ObjectNode payload = JSON.createObjectNode()
                .put("eventId", event.eventId()).put("eventType", event.type().name())
                .put("category", inventoryCategory ? "INVENTORY" : "WORLD")
                .put("priority", event.priority().name())
                .put("source", "MINECRAFT_INVENTORY_WORLD_OBSERVER")
                .put("companionId", snapshot.companionId()).put("tick", snapshot.tick())
                .put("occurredAt", snapshot.observedAt().toString())
                .put("previousValue", event.previousValue()).put("currentValue", event.currentValue());
        if (snapshot.behaviorId() != null) payload.put("behaviorId", snapshot.behaviorId());
        ObjectNode inventory = payload.putObject("inventory")
                .put("slots", snapshot.inventorySlots()).put("freeSlots", snapshot.freeInventorySlots());
        ObjectNode counts = inventory.putObject("counts");
        snapshot.inventory().forEach(counts::put);
        if (snapshot.resourceGoal() != null) inventory.putObject("goal")
                .put("itemId", snapshot.resourceGoal().itemId())
                .put("requiredCount", snapshot.resourceGoal().requiredCount())
                .put("currentCount", snapshot.inventory().getOrDefault(snapshot.resourceGoal().itemId(), 0));
        payload.putObject("world").put("dimension", snapshot.dimension())
                .put("timeOfDay", snapshot.timeOfDay().name()).put("weather", snapshot.weather().name());
        if (snapshot.target() != null) {
            InventoryWorldEventTracker.Target target = snapshot.target();
            payload.putObject("target").put("identity", target.identity())
                    .put("kind", target.kind().name()).put("present", target.present())
                    .put("blockId", target.blockId() == null ? "" : target.blockId())
                    .put("blockFingerprint", target.blockFingerprint())
                    .put("containerType", target.containerType() == null ? "" : target.containerType())
                    .put("containerFingerprint", target.containerFingerprint());
        }
        sendEnvelope("inventory_world_event", payload);
    }

    private void publishStatusOnServerThread() {
        if (socket == null || sessionId == null) return;
        ArrayNode companions = JSON.createArrayNode();
        for (CompanionRegistry.RuntimeSnapshot snapshot : registry.runtimeSnapshots(true)) {
            boolean activeBehavior = snapshot.behaviorId() != null
                    && !snapshot.behaviorState().equalsIgnoreCase("IDLE");
            ObjectNode status = companions.addObject()
                    .put("companionId", snapshot.companionId())
                    .put("ownerId", snapshot.ownerId())
                    .put("displayName", snapshot.displayName())
                    .put("worldId", worldId())
                    .put("dimension", snapshot.dimension())
                    .put("bodyState", snapshot.bodyState().toLowerCase(Locale.ROOT))
                    .put("behaviorRevision", activeBehavior ? snapshot.behaviorRevision() : 0L)
                    .put("controlEpoch", snapshot.controlEpoch())
                    .put("runtimeConnected", true)
                    .put("observedAt", Instant.now().toString());
            status.putObject("position").put("x", snapshot.x()).put("y", snapshot.y()).put("z", snapshot.z());
            status.putObject("vitals").put("health", snapshot.health()).put("maxHealth", snapshot.maxHealth())
                    .put("food", snapshot.foodLevel()).put("air", snapshot.airSupply())
                    .put("onFire", snapshot.onFire()).put("inLava", snapshot.inLava());
            ObjectNode inventory = status.putObject("inventory").put("freeSlots", snapshot.freeInventorySlots());
            ObjectNode counts = inventory.putObject("counts");
            snapshot.inventory().forEach(counts::put);
            putFacts(status, "equipment", snapshot.equipment());
            putFacts(status, "vehicle", snapshot.vehicle());
            putFacts(status, "menu", snapshot.menu());
            putFacts(status, "sleep", snapshot.sleep());
            putFacts(status, "fishing", snapshot.fish());
            putFacts(status, "glide", snapshot.glide());
            putFacts(status, "bucket", snapshot.bucket());
            putFacts(status, "crop", snapshot.crop());
            putFacts(status, "breed", snapshot.breed());
            putFacts(status, "trade", snapshot.trade());
            putFacts(status, "enchant", snapshot.enchant());
            putFacts(status, "brew", snapshot.brew());
            ArrayNode knownContainers = status.putArray("observedContainers");
            snapshot.visibleContainers().forEach(container -> knownContainers.addObject()
                    .put("type", container.type()).put("dimension", container.dimension())
                    .put("x", container.x()).put("y", container.y()).put("z", container.z())
                    .put("verified", true));
            status.putObject("capabilities");
            if (activeBehavior) {
                status.put("behaviorId", snapshot.behaviorId());
                status.put("behaviorState", snapshot.behaviorState().toLowerCase(Locale.ROOT));
            }
            if (snapshot.behaviorId() != null) publishObservedLifecycle(snapshot);
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.set("companions", companions);
        sendEnvelope("companion_list", payload);
    }

    private void publishObservedLifecycle(CompanionRegistry.RuntimeSnapshot snapshot) {
        String key = snapshot.companionId() + ':' + snapshot.behaviorId();
        String current = snapshot.behaviorState().toUpperCase(Locale.ROOT);
        String previous = observedBehaviorStates.put(key, current);
        if (previous == null || previous.equals(current)) return;
        if (current.equals("IDLE")) {
            String failure = terminalFailure(snapshot);
            ObjectNode evidence = JSON.createObjectNode().put("controlEpoch", snapshot.controlEpoch())
                    .put("positionX", snapshot.x()).put("positionY", snapshot.y()).put("positionZ", snapshot.z())
                    .put("evidence", snapshot.evidenceSummary());
            appendRuntimeSnapshot(evidence, snapshot);
            appendBehaviorObservation(evidence, snapshot.behaviorObservation());
            if (failure == null) {
                sendObservedBehaviorEvent(snapshot, "completed", "completed", 1.0D, null, evidence);
            } else {
                evidence.put("failureCode", failure);
                sendObservedBehaviorEvent(snapshot, "blocked", "blocked", 0.0D, failure, evidence);
            }
        } else if (current.equals("PAUSED") && previous.equals("RUNNING")) {
            String failure = failureCode(snapshot.evidenceSummary());
            ObjectNode evidence = JSON.createObjectNode().put("controlEpoch", snapshot.controlEpoch())
                    .put("failureCode", failure).put("evidence", snapshot.evidenceSummary());
            appendRuntimeSnapshot(evidence, snapshot);
            appendBehaviorObservation(evidence, snapshot.behaviorObservation());
            sendObservedBehaviorEvent(snapshot, "blocked", "blocked", 0.0D, failure, evidence);
        }
    }

    /**
     * Copies the already observed body state into terminal behavior events.  Keep the legacy
     * positionX/Y/Z and controlEpoch fields above: Runtime uses them for reconciliation.  The
     * structured fields mirror companion_list so a terminal TaskEvent is self-contained and
     * never has to infer success from the command acknowledgement.
     */
    private void appendRuntimeSnapshot(ObjectNode evidence, CompanionRegistry.RuntimeSnapshot snapshot) {
        evidence.put("worldId", worldId())
                .put("ownerId", snapshot.ownerId())
                .put("displayName", snapshot.displayName())
                .put("dimension", snapshot.dimension())
                .put("bodyState", snapshot.bodyState().toLowerCase(Locale.ROOT))
                .put("behaviorId", snapshot.behaviorId())
                .put("behaviorState", snapshot.behaviorState().toLowerCase(Locale.ROOT))
                .put("behaviorRevision", snapshot.behaviorRevision())
                .put("runtimeConnected", snapshot.runtimeConnected());
        evidence.putObject("position").put("x", snapshot.x()).put("y", snapshot.y()).put("z", snapshot.z());
        evidence.putObject("vitals").put("health", snapshot.health()).put("maxHealth", snapshot.maxHealth())
                .put("food", snapshot.foodLevel()).put("air", snapshot.airSupply())
                .put("onFire", snapshot.onFire()).put("inLava", snapshot.inLava());
        ObjectNode inventory = evidence.putObject("inventory").put("freeSlots", snapshot.freeInventorySlots());
        ObjectNode counts = inventory.putObject("counts");
        snapshot.inventory().forEach(counts::put);
        putFacts(evidence, "equipment", snapshot.equipment());
        putFacts(evidence, "vehicle", snapshot.vehicle());
        putFacts(evidence, "menu", snapshot.menu());
        putFacts(evidence, "sleep", snapshot.sleep());
        putFacts(evidence, "fishing", snapshot.fish());
        putFacts(evidence, "glide", snapshot.glide());
        putFacts(evidence, "bucket", snapshot.bucket());
        putFacts(evidence, "crop", snapshot.crop());
        putFacts(evidence, "breed", snapshot.breed());
        putFacts(evidence, "trade", snapshot.trade());
        putFacts(evidence, "enchant", snapshot.enchant());
        putFacts(evidence, "brew", snapshot.brew());
        ArrayNode containers = evidence.putArray("observedContainers");
        snapshot.visibleContainers().forEach(container -> containers.addObject()
                .put("type", container.type()).put("dimension", container.dimension())
                .put("x", container.x()).put("y", container.y()).put("z", container.z())
                .put("verified", true));
    }

    private static void appendBehaviorObservation(ObjectNode evidence,
                                                   CompanionRegistry.BehaviorObservation observation) {
        if (observation == null) return;
        evidence.put("failureCode", observation.failureCode())
                .put("item", observation.itemId())
                .put("requested", observation.requested())
                .put("available", observation.available());
        ArrayNode candidates = evidence.putArray("candidates");
        observation.candidates().forEach(candidate -> candidates.addObject()
                .put("block", candidate.block()).put("dimension", candidate.dimension())
                .put("x", candidate.x()).put("y", candidate.y()).put("z", candidate.z())
                .put("distanceSquared", candidate.distanceSquared()));
        ObjectNode details = evidence.putObject("details");
        observation.details().forEach(details::put);
    }

    private static void putFacts(ObjectNode parent, String name, java.util.Map<String, String> facts) {
        ObjectNode object = parent.putObject(name);
        facts.forEach(object::put);
    }

    private void sendObservedBehaviorEvent(CompanionRegistry.RuntimeSnapshot snapshot, String event, String state,
                                           double progress, String failureCode, ObjectNode evidence) {
        ObjectNode payload = JSON.createObjectNode().put("eventId", UUID.randomUUID().toString())
                .put("behaviorId", snapshot.behaviorId()).put("companionId", snapshot.companionId())
                .put("event", event).put("state", state).put("revision", snapshot.behaviorRevision() + 1)
                .put("tick", server.getTickCount()).put("progress", progress).put("occurredAt", Instant.now().toString());
        if (failureCode != null) payload.put("failureCode", failureCode).put("message", "行为已安全停止：" + failureCode);
        payload.set("snapshot", evidence);
        sendEnvelope("behavior_event", payload);
        registry.recordRuntimeLifecyclePublished(snapshot.behaviorId());
    }

    private static String failureCode(String evidence) {
        if (evidence == null) return "ACTION_BLOCKED";
        int start = evidence.indexOf("failure=");
        if (start < 0) return "ACTION_BLOCKED";
        start += "failure=".length();
        int end = evidence.indexOf(' ', start);
        return evidence.substring(start, end < 0 ? evidence.length() : end);
    }

    private static String terminalFailure(CompanionRegistry.RuntimeSnapshot snapshot) {
        String evidence = snapshot.evidenceSummary();
        if (evidence != null && evidence.contains("success=true")) return null;
        String failure = failureCode(evidence);
        if (!failure.equals("ACTION_BLOCKED") && !failure.equals("NONE")) return failure;
        CompanionRegistry.BehaviorObservation observation = snapshot.behaviorObservation();
        if (observation == null || observation.failureCode().isBlank()
                || observation.failureCode().equals("NONE")
                || observation.failureCode().equals("VERIFIED")) return null;
        return observation.failureCode();
    }

    private void sendEnvelope(String type, JsonNode payload) {
        WebSocket current = socket;
        String currentSession = sessionId;
        if (current == null || currentSession == null || current.isOutputClosed()) return;
        ObjectNode envelope = JSON.createObjectNode()
                .put("protocol", PROTOCOL)
                .put("type", type)
                .put("sessionId", currentSession)
                .put("worldId", worldId())
                .put("sequence", outgoingSequence.incrementAndGet())
                .put("timestamp", System.currentTimeMillis());
        envelope.set("payload", payload);
        current.sendText(write(envelope), true);
    }

    private String worldId() {
        return server.getWorldData().getLevelName().replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private void disconnected(long attempt, WebSocket expected, String reason) {
        if (!connections.deactivate(attempt, expected)) return;
        if (socket == expected) socket = null;
        sessionId = null;
        pendingPlayerRequests.clear();
        pendingPlayerRequestTimes.clear();
        playerRequestTimes.clear();
        ownerActivityTimes.clear();
        observedBehaviorStates.clear();
        entityEvents.clear();
        survivalEvents.clear();
        if (!closed) logger.warn("Runtime bridge disconnected: {}; companion enters safe pause", reason);
        server.execute(() -> {
            if (!connections.isLatestAttempt(attempt) && socket != null && sessionId != null) return;
            registry.setRuntimeConnected(false);
            registry.runtimeDisconnected();
        });
    }

    private void closeSocket(int code, String reason) {
        WebSocket current = socket;
        if (current != null) current.sendClose(code, reason);
    }

    private void cleanupTransientState(long now) {
        pendingPlayerRequestTimes.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= PLAYER_REQUEST_TTL_MILLIS) return false;
            pendingPlayerRequests.remove(entry.getKey());
            return true;
        });
        playerRequestTimes.entrySet().removeIf(entry -> now - entry.getValue() > 60_000);
        ownerActivityTimes.entrySet().removeIf(entry -> now - entry.getValue() > 10_000);
        while (observedBehaviorStates.size() > 512) {
            observedBehaviorStates.remove(observedBehaviorStates.keySet().iterator().next());
        }
    }

    private static String write(JsonNode value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to encode Runtime bridge JSON", impossible);
        }
    }

    /** Instance JSON is optional; JVM properties remain the highest-priority test/advanced override. */
    record BridgeSettings(boolean enabled, URI uri, Path tokenFile, String installationId, String instanceId,
                                  String launcherType) {
        static BridgeSettings load(Logger logger) { return load(Path.of("config", "minecraft-ai-companion", "runtime.json"),logger); }
        static BridgeSettings load(Path config,Logger logger) {
            config=config.toAbsolutePath().normalize();
            String url = "ws://127.0.0.1:8766";
            String token = "runtime.token";
            String installation = null;
            String instance = null;
            String launcher = null;
            boolean enabled = true;
            if (Files.isRegularFile(config)) {
                try {
                    JsonNode root = JSON.readTree(Files.readString(config, StandardCharsets.UTF_8));
                    if (root.path("schemaVersion").asInt(0) != 1) throw new IOException("unsupported schemaVersion");
                    enabled = root.path("enabled").asBoolean(true);
                    url = root.path("runtimeUrl").asText(url);
                    token = root.path("tokenFile").asText(token);
                    installation = textOrNull(root, "installationId");
                    instance = textOrNull(root, "instanceId");
                    launcher = textOrNull(root, "launcherType");
                } catch (IOException | RuntimeException failure) {
                    logger.warn("Runtime bridge ignored invalid instance runtime.json ({})", failure.getClass().getSimpleName());
                }
            }
            String propertyUrl = System.getProperty("mccompanion.runtime.url");
            if (propertyUrl != null && !propertyUrl.isBlank()) url = propertyUrl;
            String propertyToken = System.getProperty("mccompanion.runtime.tokenFile");
            Path tokenPath;
            if (propertyToken != null && !propertyToken.isBlank()) tokenPath = Path.of(propertyToken);
            else {
                Path parsed = Path.of(token);
                tokenPath = parsed.isAbsolute() ? parsed : config.getParent().resolve(parsed);
            }
            return new BridgeSettings(enabled, URI.create(url), tokenPath.toAbsolutePath().normalize(), installation, instance, launcher);
        }
        private static String textOrNull(JsonNode root, String field) {
            String value = root.path(field).asText(null);
            return value == null || value.isBlank() ? null : value;
        }
    }

    @Override
    public void close() {
        closed = true;
        connections.invalidate();
        WebSocket current = socket;
        socket = null;
        sessionId = null;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping");
        registry.runtimeDisconnected();
        registry.setRuntimeConnected(false);
        pendingPlayerRequests.clear();
        pendingPlayerRequestTimes.clear();
        playerRequestTimes.clear();
        ownerActivityTimes.clear();
        observedBehaviorStates.clear();
        entityEvents.clear();
        survivalEvents.clear();
        executor.shutdownNow();
    }

    private final class Listener implements WebSocket.Listener {
        private final long attempt;
        private final StringBuilder partial = new StringBuilder();

        private Listener(long attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (closed || !connections.activate(attempt, webSocket)) {
                webSocket.sendClose(1000, "connection superseded");
                return;
            }
            socket = webSocket;
            sendHello(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!connections.isCurrent(attempt, webSocket)) return null;
            if (partial.length() + data.length() > 1_048_576) {
                webSocket.sendClose(1009, "message too large");
                return null;
            }
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                handle(attempt, webSocket, text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            disconnected(attempt, webSocket, reason == null || reason.isBlank() ? "closed" : reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            disconnected(attempt, webSocket, error.getClass().getSimpleName());
        }
    }
}
