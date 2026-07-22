package com.mccompanion.minecraft.v121;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Issues short-lived opaque handles for the exact menu currently open on a connected body.
 * Handles are process-local capabilities: reconnect/restart, expiry, close, or menu replacement invalidates them.
 */
public final class MenuSessionTracker {
    /*
     * Leave enough time for an external Brain to inspect the menu, persist the
     * Observation, and dispatch the next graph node. The token remains bound to
     * the exact process-local menu instance and is invalidated on replacement or
     * close, so extending this bounded window does not widen its authority.
     */
    private static final long SESSION_TTL_NANOS = Duration.ofSeconds(60).toNanos();
    private static final long SESSION_TTL_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<UUID, State> SESSIONS = new ConcurrentHashMap<>();

    private MenuSessionTracker() { }

    public static Snapshot inspect(CompanionPlayer body) {
        return inspectAt(body, System.nanoTime(), System.currentTimeMillis());
    }

    static Snapshot inspectAt(CompanionPlayer body, long nowNanos, long nowEpochMillis) {
        AbstractContainerMenu menu = body.containerMenu;
        if (menu == body.inventoryMenu) {
            SESSIONS.remove(body.getUUID());
            return null;
        }
        State state = SESSIONS.compute(body.getUUID(), (ignored, previous) -> {
            if (previous != null && previous.menu == menu && previous.containerId == menu.containerId
                    && !isExpired(previous.issuedAtNanos, nowNanos)) {
                return previous;
            }
            return new State(token(), menu, menu.containerId, nowNanos,
                    nowEpochMillis + SESSION_TTL_MILLIS);
        });
        return new Snapshot(state.token, state.containerId, state.expiresAtEpochMillis, menu);
    }

    public static Validation validate(CompanionPlayer body, String token) {
        return validateAt(body, token, System.nanoTime());
    }

    static Validation validateAt(CompanionPlayer body, String token, long nowNanos) {
        if (token == null || token.isBlank()) return Validation.failure("MENU_SESSION_REQUIRED");
        State state = SESSIONS.get(body.getUUID());
        if (state == null || !constantTimeEquals(state.token, token)) {
            return Validation.failure("MENU_SESSION_INVALID");
        }
        if (isExpired(state.issuedAtNanos, nowNanos)) {
            SESSIONS.remove(body.getUUID(), state);
            return Validation.failure("MENU_SESSION_EXPIRED");
        }
        if (body.containerMenu == body.inventoryMenu || body.containerMenu != state.menu
                || body.containerMenu.containerId != state.containerId) {
            SESSIONS.remove(body.getUUID(), state);
            return Validation.failure("MENU_SESSION_CHANGED");
        }
        return new Validation(true, "OK", state.menu);
    }

    public static void invalidate(UUID companionId) {
        if (companionId != null) SESSIONS.remove(companionId);
    }

    static boolean isExpired(long issuedAtNanos, long nowNanos) {
        return nowNanos - issuedAtNanos > SESSION_TTL_NANOS;
    }

    private static String token() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] left = expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] right = actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return java.security.MessageDigest.isEqual(left, right);
    }

    private record State(String token, AbstractContainerMenu menu, int containerId,
                         long issuedAtNanos, long expiresAtEpochMillis) { }

    public record Snapshot(String token, int containerId, long expiresAtEpochMillis,
                           AbstractContainerMenu menu) { }

    public record Validation(boolean valid, String code, AbstractContainerMenu menu) {
        private static Validation failure(String code) {
            return new Validation(false, code, null);
        }
    }
}
