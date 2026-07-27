package com.mccompanion.minecraft.v120;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Issues short-lived, process-local handles for the exact open menu instance. */
public final class MenuSessionTracker {
    private static final long TTL_NANOS = Duration.ofSeconds(60).toNanos();
    private static final long TTL_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<UUID, State> SESSIONS = new ConcurrentHashMap<>();

    private MenuSessionTracker() {
    }

    public static Snapshot inspect(CompanionPlayer body) {
        AbstractContainerMenu menu = body.containerMenu;
        if (menu == body.inventoryMenu) {
            SESSIONS.remove(body.getUUID());
            return null;
        }
        long now = System.nanoTime();
        long epochMillis = System.currentTimeMillis();
        State state = SESSIONS.compute(body.getUUID(), (ignored, previous) -> {
            if (previous != null
                    && previous.menu == menu
                    && previous.containerId == menu.containerId
                    && now - previous.issuedAtNanos <= TTL_NANOS) {
                return previous;
            }
            return new State(token(), menu, menu.containerId, now, epochMillis + TTL_MILLIS);
        });
        return new Snapshot(state.token, state.containerId, state.expiresAtEpochMillis, state.menu);
    }

    public static Validation validate(CompanionPlayer body, String token) {
        if (token == null || token.isBlank()) return Validation.failure("MENU_SESSION_REQUIRED");
        State state = SESSIONS.get(body.getUUID());
        if (state == null || !constantTimeEquals(state.token, token)) {
            return Validation.failure("MENU_SESSION_INVALID");
        }
        if (System.nanoTime() - state.issuedAtNanos > TTL_NANOS) {
            SESSIONS.remove(body.getUUID(), state);
            return Validation.failure("MENU_SESSION_EXPIRED");
        }
        if (body.containerMenu == body.inventoryMenu
                || body.containerMenu != state.menu
                || body.containerMenu.containerId != state.containerId) {
            SESSIONS.remove(body.getUUID(), state);
            return Validation.failure("MENU_SESSION_CHANGED");
        }
        return new Validation(true, "OK", state.menu);
    }

    public static void invalidate(UUID companionId) {
        if (companionId != null) SESSIONS.remove(companionId);
    }

    private static String token() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private record State(
            String token,
            AbstractContainerMenu menu,
            int containerId,
            long issuedAtNanos,
            long expiresAtEpochMillis) {
    }

    public record Snapshot(
            String token,
            int containerId,
            long expiresAtEpochMillis,
            AbstractContainerMenu menu) {
    }

    public record Validation(boolean valid, String code, AbstractContainerMenu menu) {
        private static Validation failure(String code) {
            return new Validation(false, code, null);
        }
    }
}
