package com.mccompanion.core.body.menu;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Opaque, single-observation handles. Native bindings supply identity and observation revision. */
public final class MenuHandleStore {
    private static final long TTL = Duration.ofSeconds(60).toNanos();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<UUID, Bound> handles = new HashMap<>();
    public record Handle(String token, long expiresAtEpochMillis, long revision) { }
    private record Bound(Handle handle, String menu, long issuedNanos) { }

    public synchronized Handle inspect(UUID companion, String menu, long revision, long nanos, long millis) {
        handles.values().removeIf(value -> expired(value.issuedNanos(), nanos));
        Bound previous = handles.get(companion);
        if (previous != null && previous.menu().equals(menu) && previous.handle().revision() == revision) return previous.handle();
        if (handles.size() >= 1024 && previous == null) throw new IllegalStateException("MENU_HANDLE_LIMIT");
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        Handle handle = new Handle(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), millis + 60_000, revision);
        handles.put(companion, new Bound(handle, menu, nanos));
        return handle;
    }

    public synchronized String validate(UUID companion, String menu, long revision, String token, long nanos) {
        if (token == null || token.isBlank()) return "MENU_SESSION_REQUIRED";
        Bound bound = handles.get(companion);
        if (bound == null || !java.security.MessageDigest.isEqual(
                bound.handle().token().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) return "MENU_SESSION_INVALID";
        String failure = expired(bound.issuedNanos(), nanos) ? "MENU_SESSION_EXPIRED"
                : !bound.menu().equals(menu) ? "MENU_SESSION_CHANGED"
                : bound.handle().revision() != revision ? "MENU_OBSERVATION_STALE" : null;
        if (failure != null) handles.remove(companion);
        return failure;
    }
    public synchronized void invalidate(UUID companion) { handles.remove(companion); }
    public static boolean expired(long issuedNanos, long nanos) { return nanos - issuedNanos > TTL; }
}
