package com.mccompanion.minecraft.bridge;

import java.util.*;
import java.util.function.BiFunction;

/** Server-thread request correlation and bounded rate windows, containing identities only. */
public final class PlayerRequestChannel {
    public enum Result { ACCEPTED, DISCONNECTED, LIMIT, RATE_LIMIT, NO_COMPANION, INVALID_TEXT }
    private record Pending(UUID owner, long createdAt) { }
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<UUID, Long> playerTimes = new LinkedHashMap<>();
    private final Map<String, Long> activityTimes = new LinkedHashMap<>();

    public Result submit(UUID owner, String companion, String text, long now,
                         BiFunction<String, Map<String, Object>, Boolean> send) {
        expire(now);
        if (text == null || text.isBlank() || text.length() > 512) return Result.INVALID_TEXT;
        if (companion == null) return Result.NO_COMPANION;
        if (pending.size() >= 128) return Result.LIMIT;
        Long previous = playerTimes.put(owner, now);
        trim(playerTimes, 512);
        if (previous != null && now - previous < 1500) return Result.RATE_LIMIT;
        String request = UUID.randomUUID().toString();
        pending.put(request, new Pending(owner, now));
        if (!send.apply("player_request", Map.of("requestId", request, "companionId", companion,
                "ownerId", owner.toString(), "text", text))) {
            pending.remove(request);
            return Result.DISCONNECTED;
        }
        return Result.ACCEPTED;
    }

    public UUID replyOwner(String request, long now) {
        expire(now);
        Pending value = pending.remove(request);
        return value == null ? null : value.owner();
    }

    public boolean publishActivity(String key, long now) {
        expire(now);
        Long previous = activityTimes.put(key, now);
        trim(activityTimes, 128);
        return previous == null || now - previous >= 250;
    }

    public void expire(long now) {
        pending.values().removeIf(value -> now - value.createdAt() > 30_000);
        playerTimes.values().removeIf(value -> now - value > 60_000);
        activityTimes.values().removeIf(value -> now - value > 10_000);
    }
    public void clear() { pending.clear(); playerTimes.clear(); activityTimes.clear(); }
    private static void trim(Map<?, ?> values, int maximum) {
        while (values.size() > maximum) values.remove(values.keySet().iterator().next());
    }
}
