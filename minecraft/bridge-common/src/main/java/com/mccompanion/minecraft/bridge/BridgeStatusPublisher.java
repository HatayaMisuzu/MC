package com.mccompanion.minecraft.bridge;

import com.mccompanion.core.body.BodySnapshots;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Shared status projection and lifecycle transitions, fed exclusively by observed immutable facts. */
public final class BridgeStatusPublisher {
    public record ObservedBody(BodySnapshots.RuntimeSnapshot body, Map<String, Object> localWorld,
                               Map<String, Object> navigation, List<?> containers) {
        public ObservedBody {
            Objects.requireNonNull(body);
            localWorld = BridgeValues.freeze(localWorld);
            navigation = BridgeValues.freeze(navigation);
            containers = (List<?>) BridgeValues.freeze(Map.of("containers", containers)).get("containers");
        }
    }
    private final Map<String, String> states = new LinkedHashMap<>();
    private final Set<String> announced = new HashSet<>();
    public void clear() { states.clear(); announced.clear(); }
    public boolean announced(String companion) { return announced.contains(companion); }
    public void accepted(String companion, String behavior, String state) {
        remember(companion + ':' + behavior, state.toUpperCase(Locale.ROOT));
    }
    private String remember(String key, String state) {
        String old = states.put(key, state);
        while (states.size() > 512) states.remove(states.keySet().iterator().next());
        return old;
    }
    public void publish(List<ObservedBody> observed, String world, long tick,
                        BiFunction<String, Map<String, Object>, Boolean> send, Consumer<String> published) {
        List<Map<String, Object>> statuses = observed.stream().map(view -> status(view, world)).toList();
        if (!send.apply("companion_list", Map.of("companions", statuses))) return;
        announced.clear();
        for (ObservedBody view : observed) {
            var body = view.body(); announced.add(body.companionId());
            if (body.behaviorId() == null) continue;
            String current = body.behaviorState().toUpperCase(Locale.ROOT);
            String key = body.companionId() + ':' + body.behaviorId();
            String previous = states.get(key);
            if (previous == null || previous.equals(current)) { remember(key, current); continue; }
            String failure;
            if (current.equals("IDLE")) failure = terminalFailure(body);
            else if (current.equals("PAUSED") && previous.equals("RUNNING")) failure = failureCode(body.evidenceSummary());
            else { remember(key, current); continue; }
            Map<String, Object> evidence = facts(body, world);
            evidence.putAll(map("controlEpoch", body.controlEpoch(), "positionX", body.x(),
                    "positionY", body.y(), "positionZ", body.z(), "evidence", body.evidenceSummary()));
            evidence.put("observedContainers", view.containers());
            evidence.putAll(observation(body.behaviorObservation()));
            boolean success = failure == null;
            if (!success) evidence.put("failureCode", failure);
            Map<String, Object> event = map("eventId", UUID.randomUUID().toString(), "behaviorId", body.behaviorId(),
                    "companionId", body.companionId(), "event", success ? "completed" : "blocked",
                    "state", success ? "completed" : "blocked", "revision", body.behaviorRevision() + 1,
                    "tick", tick, "progress", success ? 1.0 : 0.0, "occurredAt", Instant.now().toString(), "snapshot", evidence);
            if (!success) event.putAll(map("failureCode", failure, "message", "Behavior stopped safely: " + failure));
            if (send.apply("behavior_event", event)) {
                remember(key, current);
                published.accept(body.behaviorId());
            }
        }
    }
    private static Map<String, Object> status(ObservedBody view, String world) {
        var body = view.body(); var status = facts(body, world);
        boolean active = body.behaviorId() != null && !body.behaviorState().equalsIgnoreCase("IDLE");
        status.put("companionId", body.companionId()); status.put("controlEpoch", body.controlEpoch());
        status.put("behaviorRevision", active ? body.behaviorRevision() : 0L);
        if (!active) { status.remove("behaviorId"); status.remove("behaviorState"); }
        status.put("observedAt", Instant.now().toString()); status.put("capabilities", Map.of());
        status.put("observedContainers", view.containers()); status.put("localWorld", view.localWorld());
        status.put("navigation", view.navigation());
        return status;
    }
    public static Map<String, Object> facts(BodySnapshots.RuntimeSnapshot body, String world) {
        return map("worldId", world, "ownerId", body.ownerId(), "displayName", body.displayName(),
                "dimension", body.dimension(), "bodyState", body.bodyState().toLowerCase(Locale.ROOT),
                "behaviorId", body.behaviorId(), "behaviorState", body.behaviorState().toLowerCase(Locale.ROOT),
                "behaviorRevision", body.behaviorRevision(), "runtimeConnected", body.runtimeConnected(),
                "position", map("x", body.x(), "y", body.y(), "z", body.z()),
                "vitals", map("health", body.health(), "maxHealth", body.maxHealth(), "food", body.foodLevel(),
                        "air", body.airSupply(), "onFire", body.onFire(), "inLava", body.inLava()),
                "inventory", map("freeSlots", body.freeInventorySlots(), "counts", body.inventory()),
                "equipment", body.equipment(), "vehicle", body.vehicle(), "menu", body.menu(), "sleep", body.sleep(),
                "fishing", body.fish(), "glide", body.glide(), "bucket", body.bucket(), "crop", body.crop(),
                "breed", body.breed(), "trade", body.trade(), "enchant", body.enchant(), "brew", body.brew());
    }
    public static Map<String, Object> observation(BodySnapshots.BehaviorObservation value) {
        if (value == null) return Map.of();
        return map("failureCode", value.failureCode(), "item", value.itemId(), "requested", value.requested(),
                "available", value.available(), "details", value.details(), "candidates", value.candidates().stream()
                        .map(candidate -> map("block", candidate.block(), "dimension", candidate.dimension(),
                                "x", candidate.x(), "y", candidate.y(), "z", candidate.z(),
                                "distanceSquared", candidate.distanceSquared())).toList());
    }
    private static String failureCode(String evidence) {
        if (evidence == null) return "ACTION_BLOCKED";
        int start = evidence.indexOf("failure=");
        if (start < 0) return "ACTION_BLOCKED";
        start += "failure=".length(); int end = evidence.indexOf(' ', start);
        return evidence.substring(start, end < 0 ? evidence.length() : end);
    }
    private static String terminalFailure(BodySnapshots.RuntimeSnapshot body) {
        if (body.evidenceSummary() != null && body.evidenceSummary().contains("success=true")) return null;
        String failure = failureCode(body.evidenceSummary());
        if (!failure.equals("ACTION_BLOCKED") && !failure.equals("NONE")) return failure;
        var value = body.behaviorObservation();
        if (value == null || value.failureCode().isBlank() || value.failureCode().equals("NONE") || value.failureCode().equals("VERIFIED")) return null;
        return value.failureCode();
    }
    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) value.put((String) entries[i], entries[i + 1]);
        return value;
    }
}
