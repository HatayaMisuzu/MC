package com.mccompanion.minecraft.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.minecraft.v121.CompanionRegistry;
import com.mccompanion.minecraft.v121.SkillParameters;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import net.minecraft.network.chat.Component;
import com.mccompanion.minecraft.v121.CompanionCommands;
import org.slf4j.Logger;

/** Optional, reconnecting local WebSocket bridge. All game mutations are re-entered on the server thread. */
final class RuntimeBridge implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROTOCOL = "mc-companion/1";

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
    private final Map<String, String> observedBehaviorStates = new HashMap<>();
    private final Map<String, UUID> pendingPlayerRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerRequestTimes = new HashMap<>();
    private final Map<String, Boolean> deliveredConversationEvents = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 512;
                }
            });
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
            client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + token)
                    .buildAsync(uri, new Listener())
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
                .put("primitive_lifecycle", true)
                .put("NavigateTo", true)
                .put("FollowOwner", true)
                .put("WithdrawFromStorage", true)
                .put("DepositToStorage", true)
                .put("CraftItem", true)
                .put("ExploreArea", true)
                .put("CollectResource", true)
                .put("MineResourceVein", true)
                .put("SmeltItem", true)
                .put("DeliverItem", true)
                .put("EatAndRecover", true)
                .put("runtime_safe_idle", true);
        ObjectNode hello = JSON.createObjectNode().put("protocol", PROTOCOL).put("type", "hello");
        hello.set("payload", payload);
        webSocket.sendText(write(hello), true);
    }

    private void handle(String text) {
        final JsonNode message;
        try {
            message = JSON.readTree(text);
        } catch (IOException malformed) {
            logger.warn("Runtime bridge ignored malformed JSON");
            return;
        }
        String type = message.path("type").asText("");
        if (type.equals("hello_ack")) {
            if (!message.path("accepted").asBoolean(false)) {
                logger.warn("Runtime rejected bridge handshake: {}", message.path("code").asText("UNKNOWN"));
                closeSocket(1008, "handshake rejected");
                return;
            }
            sessionId = message.path("sessionId").asText();
            outgoingSequence.set(0);
            server.execute(() -> registry.setRuntimeConnected(true));
            logger.info("Runtime bridge connected: protocol={} safeIdleOnDisconnect=true", PROTOCOL);
            publishStatus();
            return;
        }
        if (type.equals("query") && message.path("name").asText().equals("list_companions")) {
            publishStatus();
        } else if (type.equals("command")) {
            server.execute(() -> processCommand(message.path("payload")));
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
        Long previous = playerRequestTimes.put(owner.getUUID(), now);
        if (previous != null && now - previous < 1500) return new CompanionCommands.TextRequestResult(false, "请求过快，请稍等片刻。");
        String companionId = registry.runtimeSnapshots(true).stream()
                .filter(value -> value.ownerId().equals(owner.getUUID().toString()))
                .map(CompanionRegistry.RuntimeSnapshot::companionId).findFirst().orElse(null);
        if (companionId == null) return new CompanionCommands.TextRequestResult(false, "你还没有可用的 Companion。");
        String requestId = UUID.randomUUID().toString();
        pendingPlayerRequests.put(requestId, owner.getUUID());
        ObjectNode payload = JSON.createObjectNode().put("requestId", requestId).put("companionId", companionId)
                .put("ownerId", owner.getUUID().toString()).put("text", text);
        sendEnvelope("player_request", payload);
        return new CompanionCommands.TextRequestResult(true, "收到，我先结合当前世界状态理解这个目标。");
    }

    private void deliverPlayerReply(JsonNode payload) {
        String requestId = payload.path("requestId").asText("");
        UUID ownerId = pendingPlayerRequests.remove(requestId);
        if (ownerId == null) return;
        String reply = payload.path("reply").asText("请求已处理。");
        if (reply.length() > 512) reply = reply.substring(0, 512);
        String finalReply = reply;
        server.execute(() -> {
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            if (owner != null) owner.sendSystemMessage(Component.literal("[伙伴] " + finalReply));
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
                    ServerPlayer owner = server.getPlayerList().getPlayer(UUID.fromString(snapshot.ownerId()));
                    if (owner != null) {
                        if (deliveredConversationEvents.containsKey(eventId)) {
                            sendConversationDeliveryAck(eventId, companionId);
                            return;
                        }
                        owner.sendSystemMessage(Component.literal("[伙伴] " + finalReply));
                        logger.info("conversation_delivered_to_game event={} kind={} companion={}",
                                payload.path("eventId").asText("unknown"), payload.path("kind").asText("MESSAGE"), companionId);
                        deliveredConversationEvents.put(eventId, true);
                        sendConversationDeliveryAck(eventId, companionId);
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
                JsonNode parameters = arguments.path("parameters");
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
                target.path("dimension").asText("minecraft:overworld"), x, y, z); }
        catch (IllegalArgumentException invalid) { return null; }
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
        payload.putObject("snapshot").put("controlEpoch", currentEpoch(companionId));
        sendEnvelope("behavior_event", payload);
        if (result.behaviorId() != null) {
            observedBehaviorStates.put(companionId + ':' + result.behaviorId(), result.state().toUpperCase(Locale.ROOT));
        }
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
        if (closed || socket == null || sessionId == null) return;
        server.execute(this::publishStatusOnServerThread);
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
            ObjectNode evidence = JSON.createObjectNode().put("controlEpoch", snapshot.controlEpoch())
                    .put("positionX", snapshot.x()).put("positionY", snapshot.y()).put("positionZ", snapshot.z())
                    .put("evidence", snapshot.evidenceSummary());
            appendBehaviorObservation(evidence, snapshot.behaviorObservation());
            sendObservedBehaviorEvent(snapshot, "completed", "completed", 1.0D, null, evidence);
        } else if (current.equals("PAUSED") && previous.equals("RUNNING")) {
            String failure = failureCode(snapshot.evidenceSummary());
            ObjectNode evidence = JSON.createObjectNode().put("controlEpoch", snapshot.controlEpoch())
                    .put("failureCode", failure).put("evidence", snapshot.evidenceSummary());
            appendBehaviorObservation(evidence, snapshot.behaviorObservation());
            sendObservedBehaviorEvent(snapshot, "blocked", "blocked", 0.0D, null, evidence);
        }
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

    private void disconnected(WebSocket expected, String reason) {
        if (socket == expected) socket = null;
        sessionId = null;
        if (!closed) logger.warn("Runtime bridge disconnected: {}; companion enters safe pause", reason);
        server.execute(() -> {
            registry.setRuntimeConnected(false);
            registry.runtimeDisconnected();
        });
    }

    private void closeSocket(int code, String reason) {
        WebSocket current = socket;
        if (current != null) current.sendClose(code, reason);
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
        WebSocket current = socket;
        socket = null;
        sessionId = null;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping");
        registry.runtimeDisconnected();
        registry.setRuntimeConnected(false);
        executor.shutdownNow();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            sendHello(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (partial.length() + data.length() > 1_048_576) {
                webSocket.sendClose(1009, "message too large");
                return null;
            }
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                handle(text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            disconnected(webSocket, reason == null || reason.isBlank() ? "closed" : reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            disconnected(webSocket, error.getClass().getSimpleName());
        }
    }
}
