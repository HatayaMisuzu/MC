package com.mccompanion.minecraft.forge;

import com.mccompanion.core.body.BodySnapshots;
import com.mccompanion.minecraft.bridge.BridgeStatusPublisher;
import com.mccompanion.minecraft.bridge.PlayerRequestChannel;

import com.mccompanion.minecraft.forge.json.JsonNode;
import com.mccompanion.minecraft.forge.json.ObjectMapper;
import com.mccompanion.minecraft.forge.json.ArrayNode;
import com.mccompanion.minecraft.forge.json.ObjectNode;
import com.mccompanion.minecraft.v120.CompanionCommands;
import com.mccompanion.minecraft.v120.CompanionRegistry;
import com.mccompanion.core.body.SkillParameters;
import com.mccompanion.core.body.build.SmallBlueprint;
import com.mccompanion.minecraft.bridge.ConversationDeliveryWindow;
import com.mccompanion.minecraft.bridge.RuntimeCommandArguments;
import com.mccompanion.minecraft.bridge.EntityEventTracker;
import com.mccompanion.minecraft.bridge.InventoryWorldEventTracker;
import com.mccompanion.minecraft.bridge.SurvivalEventTracker;
import com.mccompanion.minecraft.v120.EntityEventObservationService;
import com.mccompanion.minecraft.v120.InventoryWorldEventObservationService;
import com.mccompanion.minecraft.v120.SurvivalEventObservationService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Thin Forge adapter for the bounded Runtime protocol. All world and companion mutations are
 * delegated to the version-common deterministic registry on the Minecraft server thread.
 */
final class RuntimeBridge implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROTOCOL = com.mccompanion.protocol.BuildIdentity.PROTOCOL;
    private static final int MAX_MESSAGE_CHARS = 1_048_576;

    private final MinecraftServer server;
    private final String capturedWorldId;
    private final CompanionRegistry registry;
    private final com.mccompanion.minecraft.bridge.SharedBridgeCore core;
    private final com.mccompanion.minecraft.bridge.BridgeCodec codec = new GsonBridgeCodec();
    private final Logger logger;
    private final BridgeSettings settings;
    private final PlayerRequestChannel playerRequests = new PlayerRequestChannel();
    private final BridgeStatusPublisher statusPublisher = new BridgeStatusPublisher();
    private final ConversationDeliveryWindow deliveredConversationEvents =
            new ConversationDeliveryWindow(512);
    private final EntityEventTracker entityEvents = new EntityEventTracker();
    private final EntityEventObservationService entityEventObservations;
    private final SurvivalEventTracker survivalEvents = new SurvivalEventTracker();
    private final SurvivalEventObservationService survivalEventObservations;
    private final InventoryWorldEventTracker inventoryWorldEvents = new InventoryWorldEventTracker();
    private final InventoryWorldEventObservationService inventoryWorldEventObservations;
    private volatile boolean closed;

    private RuntimeBridge(MinecraftServer server, CompanionRegistry registry, Logger logger) {
        this.server = server;
        this.capturedWorldId = server.getWorldData().getLevelName().replaceAll("[^A-Za-z0-9_.:-]", "_");
        this.registry = registry;
        this.logger = logger;
        this.settings = BridgeSettings.load(logger);
        this.entityEventObservations = new EntityEventObservationService(server);
        this.survivalEventObservations = new SurvivalEventObservationService();
        this.inventoryWorldEventObservations = new InventoryWorldEventObservationService();
        this.core = new com.mccompanion.minecraft.bridge.SharedBridgeCore(settings.uri(),
                settings.tokenFile(), hello(), codec, new com.mccompanion.minecraft.bridge.SharedBridgeCore.Host() {
            @Override public void execute(Runnable work) { server.execute(work); }
            @Override public void connected() {
                statusPublisher.clear(); registry.setRuntimeConnected(true);
                logger.info("Runtime bridge connected: session={}", core.sessionId());
            }
            @Override public void disconnected(String reason) { clearDisconnected(); }
            @Override public void message(Map<String, Object> value) { handle(JSON.valueToTree(value)); }
            @Override public void publishStatus() { publishStatusOnServerThread(); }
            @Override public void diagnostic(String code) { logger.warn("Runtime bridge: {}", code); }
        });
    }

    static RuntimeBridge start(MinecraftServer server, CompanionRegistry registry, Logger logger) {
        RuntimeBridge bridge = new RuntimeBridge(server, registry, logger);
        if (!bridge.settings.enabled()) {
            logger.info("Runtime bridge disabled by instance config; local companion control remains available");
            return bridge;
        }
        bridge.core.start();
        return bridge;
    }

    private Map<String, Object> hello() {
        return com.mccompanion.minecraft.bridge.BridgeHello.create(
                "forge-1.20.1", "1.20.1", "forge", worldId(), settings.installationId(),
                settings.instanceId(), settings.launcherType(), registry.runtimeCapabilities());
    }

    private void handle(JsonNode message) {
        String type = message.path("type").asText("");
        if (type.equals("query") && message.path("name").asText().equals("list_companions")) publishStatusOnServerThread();
        else if (type.equals("command")) processCommand(message.path("payload"));
        else if (type.equals("player_reply")) deliverPlayerReply(message.path("payload"));
        else if (type.equals("conversation_event")) deliverConversationEvent(message.path("payload"));
    }

    CompanionCommands.TextRequestResult submitPlayerText(ServerPlayer owner, String text) {
        PlayerRequestChannel.Result result = core.connected()
                ? playerRequests.submit(owner.getUUID(), registry.runtimeSnapshots(true).stream()
                        .filter(value -> value.ownerId().equals(owner.getUUID().toString()))
                        .map(BodySnapshots.RuntimeSnapshot::companionId).findFirst().orElse(null),
                        text, System.currentTimeMillis(), core::send)
                : PlayerRequestChannel.Result.DISCONNECTED;
        return new CompanionCommands.TextRequestResult(result == PlayerRequestChannel.Result.ACCEPTED, switch (result) {
            case ACCEPTED -> "收到，我会结合当前世界状态理解这个目标。";
            case DISCONNECTED -> "Runtime 未连接；复杂任务不会被静默猜测执行。";
            case LIMIT -> "待处理请求已满，请稍后重试。";
            case RATE_LIMIT -> "请求过快，请稍等片刻。";
            case NO_COMPANION -> "你还没有可用的 Companion。";
            case INVALID_TEXT -> "请求需要包含 1 至 512 个字符。";
        });
    }

    void submitOwnerBlockActivity(ServerPlayer owner, BlockPos position, String activityType) {
        if (!core.connected() || owner == null || position == null) return;
        String companionId = companionIdFor(owner);
        if (companionId == null) return;
        String dimension = owner.serverLevel().dimension().location().toString();
        String key = owner.getUUID() + ":" + activityType + ":" + dimension + ":"
                + position.getX() + ":" + position.getY() + ":" + position.getZ();
        if (!playerRequests.publishActivity(key, System.currentTimeMillis())) return;
        ObjectNode payload = JSON.createObjectNode()
                .put("companionId", companionId)
                .put("ownerId", owner.getUUID().toString())
                .put("activityType", activityType);
        payload.putObject("position")
                .put("dimension", dimension)
                .put("x", position.getX())
                .put("y", position.getY())
                .put("z", position.getZ());
        sendEnvelope("owner_activity", payload);
    }

    private String companionIdFor(ServerPlayer owner) {
        return registry.runtimeSnapshots(true).stream()
                .filter(value -> value.ownerId().equals(owner.getUUID().toString()))
                .map(BodySnapshots.RuntimeSnapshot::companionId)
                .findFirst()
                .orElse(null);
    }

    private void deliverPlayerReply(JsonNode payload) {
        String requestId = payload.path("requestId").asText("");
        UUID ownerId = playerRequests.replyOwner(requestId, System.currentTimeMillis());
        if (ownerId == null) return;
        String reply = payload.path("reply").asText("请求已处理。").strip();
        if (reply.length() > 512) reply = reply.substring(0, 512);
        String finalReply = reply;
        {
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            if (owner != null) {
                owner.sendSystemMessage(Component.translatable("mcac.chat.prefix")
                        .append(Component.literal(finalReply)));
            }
        }
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
                        companionId,
                        leaseId,
                        epoch,
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
            default -> result =
                    new CompanionRegistry.RuntimeResult(false, "UNKNOWN_COMMAND", null, 0, "FAILED");
        }
        if (!result.success()) {
            sendProtocolError(commandId, result.code());
            return;
        }
        registry.recordRuntimeCommand();
        sendCommandAccepted(commandId, result);
        if (result.behaviorId() != null
                && !commandType.equals("ACQUIRE_LEASE")
                && !commandType.equals("RENEW_LEASE")
                && !commandType.equals("RELEASE_LEASE")) {
            sendBehaviorEvent(commandId, companionId, result, commandType);
            statusPublisher.accepted(companionId, result.behaviorId(), result.state());
        }
        publishStatusOnServerThread();
    }

    private void sendQueryResult(
            String commandId,
            String companionId,
            JsonNode arguments,
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

    private void sendObservationResult(
            String commandId,
            String companionId,
            JsonNode arguments,
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

    private SkillParameters skillParameters(JsonNode parameters) {
        return com.mccompanion.minecraft.bridge.BridgeSkillArguments.decode(values(parameters));
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
        ObjectNode payload = JSON.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("behaviorId", result.behaviorId())
                .put("commandId", commandId)
                .put("companionId", companionId)
                .put("event", event)
                .put("state", result.state().toLowerCase(Locale.ROOT))
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

    private long currentEpoch(String companionId) {
        return registry.runtimeSnapshots(true).stream()
                .filter(value -> value.companionId().equals(companionId))
                .mapToLong(BodySnapshots.RuntimeSnapshot::controlEpoch)
                .findFirst()
                .orElse(0L);
    }

    /** Runs on the Minecraft server thread and observes only bounded areas around live bodies. */
    void tick() {
        if (closed || !core.connected() || server.getTickCount() % 5 != 0) return;
        // Register new/reconnected Bodies before their first authenticated event.
        if (registry.runtimeSnapshots(false).stream().anyMatch(snapshot ->
                !statusPublisher.announced(snapshot.companionId()))) publishStatusOnServerThread();
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
        core.send("player_entity_event", com.mccompanion.minecraft.bridge.BridgeEventMessages.entity(event,
                registry.locallyHandlesSafetyEvent(event.companionId(), event.type().name())));
    }

    private void sendSurvivalEvent(SurvivalEventTracker.Event event) {
        core.send("survival_event", com.mccompanion.minecraft.bridge.BridgeEventMessages.survival(event,
                registry.locallyHandlesSafetyEvent(event.snapshot().companionId(), event.type().name())));
    }

    private void sendInventoryWorldEvent(InventoryWorldEventTracker.Event event) {
        core.send("inventory_world_event", com.mccompanion.minecraft.bridge.BridgeEventMessages.inventoryWorld(event,
                registry.locallyHandlesSafetyEvent(event.snapshot().companionId(), event.type().name())));
    }

    private void publishStatusOnServerThread() {
        playerRequests.expire(System.currentTimeMillis());
        if (!core.connected()) return;
        var bodies = new java.util.ArrayList<BridgeStatusPublisher.ObservedBody>();
        for (BodySnapshots.RuntimeSnapshot snapshot : registry.runtimeSnapshots(true)) {
            ObjectNode localWorld = PrimitiveObservationService.localWorld(registry, snapshot.companionId());
            ArrayNode resources = localWorld.putArray("resources");
            if (snapshot.behaviorObservation() != null) snapshot.behaviorObservation().candidates().stream()
                    .limit(16).forEach(candidate -> {
                        // Reobserve historical candidates; their old result is not fresh evidence.
                        ObjectNode query = JSON.createObjectNode().put("tool", "block.inspect");
                        query.putObject("position").put("dimension", candidate.dimension())
                                .put("x", candidate.x()).put("y", candidate.y()).put("z", candidate.z());
                        var observed = PrimitiveObservationService.inspect(registry, snapshot.companionId(), query);
                        if (observed.success() && candidate.block().equals(observed.observation().path("block").asText()))
                            resources.add(observed.observation());
                    });
            bodies.add(new BridgeStatusPublisher.ObservedBody(snapshot, values(localWorld),
                    values(JSON.valueToTree(registry.worldNavigation(snapshot.companionId()))), containers(snapshot)));
        }
        statusPublisher.publish(bodies, worldId(), server.getTickCount(), core::send,
                registry::recordRuntimeLifecyclePublished);
    }

    private java.util.List<?> containers(BodySnapshots.RuntimeSnapshot snapshot) {
        ObjectNode wrapper = JSON.createObjectNode();
        wrapper.set("containers", PrimitiveObservationService.visibleContainers(registry, snapshot.companionId()));
        return (java.util.List<?>) values(wrapper).get("containers");
    }

    /**
     * Copies the already observed body state into terminal behavior events. Keep the legacy
     * positionX/Y/Z and controlEpoch fields for Runtime reconciliation, while mirroring the
     * companion_list shape so a terminal TaskEvent is self-contained.
     */
    private void appendRuntimeSnapshot(ObjectNode evidence, BodySnapshots.RuntimeSnapshot snapshot) {
        BridgeStatusPublisher.facts(snapshot, worldId()).forEach((key, value) -> evidence.set(key, JSON.valueToTree(value)));
        evidence.set("observedContainers", JSON.valueToTree(containers(snapshot)));
    }

    private static void appendBehaviorObservation(ObjectNode evidence, BodySnapshots.BehaviorObservation observation) {
        BridgeStatusPublisher.observation(observation).forEach((key, value) -> evidence.set(key, JSON.valueToTree(value)));
    }

    private void deliverConversationEvent(JsonNode payload) {
        String companionId = payload.path("companionId").asText("");
        String eventId = payload.path("eventId").asText("");
        String reply = payload.path("reply").asText("").strip();
        if (eventId.isEmpty() || reply.isEmpty()) return;
        if (reply.length() > 512) reply = reply.substring(0, 512);
        String finalReply = reply;
        registry.runtimeSnapshots(true).stream()
                .filter(value -> value.companionId().equals(companionId))
                .findFirst()
                .ifPresent(snapshot -> {
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
                              sendConversationDeliveryAck(eventId, companionId);
                          }
                    } catch (IllegalArgumentException ignored) {
                        logger.warn("Runtime sent an invalid owner identity for companion {}", companionId);
                    }
                });
      }

      private void sendConversationDeliveryAck(String eventId, String companionId) {
          sendEnvelope("conversation_delivery_ack", JSON.createObjectNode()
                  .put("eventId", eventId).put("companionId", companionId));
      }

      private void sendEnvelope(String type, JsonNode payload) {
        core.send(type, values(payload));
    }

    private Map<String, Object> values(JsonNode value) {
        try { return codec.decode(write(value)); }
        catch (IOException invalid) { throw new IllegalArgumentException("Invalid bridge data", invalid); }
    }

    private String worldId() {
        return capturedWorldId;
    }

    private void clearDisconnected() {
        playerRequests.clear();
        statusPublisher.clear();
        entityEvents.clear();
        survivalEvents.clear();
        inventoryWorldEvents.clear();
        registry.setRuntimeConnected(false);
        registry.runtimeDisconnected();
    }

    private static String write(JsonNode value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to encode Runtime bridge JSON", impossible);
        }
    }

    record BridgeSettings(
            boolean enabled,
            URI uri,
            Path tokenFile,
            String installationId,
            String instanceId,
            String launcherType) {
        static BridgeSettings load(Logger logger) {
            Path config = Path.of("config", "minecraft-ai-companion", "runtime.json")
                    .toAbsolutePath()
                    .normalize();
            String url = "ws://127.0.0.1:8766";
            String token = "runtime.token";
            String installation = null;
            String instance = null;
            String launcher = null;
            boolean enabled = true;
            if (Files.isRegularFile(config)) {
                try {
                    JsonNode root = JSON.readTree(Files.readString(config, StandardCharsets.UTF_8));
                    if (root.path("schemaVersion").asInt(0) != 1) {
                        throw new IOException("unsupported schemaVersion");
                    }
                    enabled = root.path("enabled").asBoolean(true);
                    url = root.path("runtimeUrl").asText(url);
                    token = root.path("tokenFile").asText(token);
                    installation = textOrNull(root, "installationId");
                    instance = textOrNull(root, "instanceId");
                    launcher = textOrNull(root, "launcherType");
                } catch (IOException | RuntimeException failure) {
                    logger.warn("Runtime bridge ignored invalid instance runtime.json ({})",
                            failure.getClass().getSimpleName());
                }
            }
            String propertyUrl = System.getProperty("mccompanion.runtime.url");
            if (propertyUrl != null && !propertyUrl.isBlank()) url = propertyUrl;
            String propertyToken = System.getProperty("mccompanion.runtime.tokenFile");
            Path tokenPath;
            if (propertyToken != null && !propertyToken.isBlank()) {
                tokenPath = Path.of(propertyToken);
            } else {
                Path parsed = Path.of(token);
                tokenPath = parsed.isAbsolute() ? parsed : config.getParent().resolve(parsed);
            }
            return new BridgeSettings(
                    enabled,
                    URI.create(url),
                    tokenPath.toAbsolutePath().normalize(),
                    installation,
                    instance,
                    launcher);
        }

        private static String textOrNull(JsonNode root, String field) {
            String value = root.path(field).asText(null);
            return value == null || value.isBlank() ? null : value;
        }
    }

    @Override
    public void close() {
        closed = true;
        core.close();
    }


}
