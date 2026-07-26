package com.mccompanion.terminal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Short-lived, hash-only, one-use browser bootstrap tickets. */
final class BootstrapTicketStore {
  enum Result {
    CONSUMED,
    NOT_FOUND,
    EXPIRED,
    WRONG_BINDING
  }

  private static final SecureRandom RANDOM = new SecureRandom();
  private final Clock clock;
  private final Duration ttl;
  private final int capacity;
  private final Map<String, Ticket> tickets = new LinkedHashMap<>();

  BootstrapTicketStore() {
    this(Clock.systemUTC(), Duration.ofSeconds(30), 32);
  }

  BootstrapTicketStore(Clock clock, Duration ttl, int capacity) {
    this.clock = clock;
    this.ttl = ttl;
    this.capacity = capacity;
    if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(2)) > 0) {
      throw new IllegalArgumentException("bootstrap ticket TTL must be 1 ms..2 minutes");
    }
    if (capacity < 1 || capacity > 128) {
      throw new IllegalArgumentException("bootstrap ticket capacity must be 1..128");
    }
  }

  synchronized String issue(String binding) {
    cleanup();
    while (tickets.size() >= capacity) {
      tickets.remove(tickets.keySet().iterator().next());
    }
    String value = token();
    tickets.put(hash(value), new Ticket(required(binding), clock.instant().plus(ttl)));
    return value;
  }

  synchronized Result consume(String value, String binding) {
    if (value == null || value.isBlank()) return Result.NOT_FOUND;
    Ticket ticket = tickets.remove(hash(value));
    if (ticket == null) return Result.NOT_FOUND;
    if (!clock.instant().isBefore(ticket.expiresAt())) return Result.EXPIRED;
    if (!constantTime(ticket.binding(), binding)) return Result.WRONG_BINDING;
    return Result.CONSUMED;
  }

  synchronized int size() {
    cleanup();
    return tickets.size();
  }

  private void cleanup() {
    Instant now = clock.instant();
    tickets.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
  }

  private static String token() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String hash(String value) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String required(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("binding is blank");
    return value;
  }

  private static boolean constantTime(String expected, String actual) {
    if (expected == null || actual == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  private record Ticket(String binding, Instant expiresAt) {}
}
