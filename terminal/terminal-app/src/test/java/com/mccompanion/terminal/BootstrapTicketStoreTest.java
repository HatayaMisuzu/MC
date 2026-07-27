package com.mccompanion.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BootstrapTicketStoreTest {
  @Test
  void ticketIsBoundOneUseAndExpires() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-26T00:00:00Z"));
    BootstrapTicketStore store =
        new BootstrapTicketStore(clock, Duration.ofSeconds(30), 4);

    String wrongBinding = store.issue("instance-a");
    assertEquals(
        BootstrapTicketStore.Result.WRONG_BINDING,
        store.consume(wrongBinding, "instance-b"));
    assertEquals(
        BootstrapTicketStore.Result.NOT_FOUND,
        store.consume(wrongBinding, "instance-a"));

    String consumed = store.issue("instance-a");
    assertEquals(
        BootstrapTicketStore.Result.CONSUMED,
        store.consume(consumed, "instance-a"));
    assertEquals(
        BootstrapTicketStore.Result.NOT_FOUND,
        store.consume(consumed, "instance-a"));

    String expired = store.issue("instance-a");
    clock.advance(Duration.ofSeconds(30));
    assertEquals(
        BootstrapTicketStore.Result.EXPIRED,
        store.consume(expired, "instance-a"));
  }

  @Test
  void capacityEvictsOldestWithoutRetainingRawTickets() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-26T00:00:00Z"));
    BootstrapTicketStore store =
        new BootstrapTicketStore(clock, Duration.ofSeconds(30), 2);
    String first = store.issue("instance");
    String second = store.issue("instance");
    String third = store.issue("instance");
    assertNotEquals(first, second);
    assertNotEquals(second, third);
    assertEquals(2, store.size());
    assertEquals(
        BootstrapTicketStore.Result.NOT_FOUND, store.consume(first, "instance"));
    assertEquals(
        BootstrapTicketStore.Result.CONSUMED, store.consume(second, "instance"));
    assertEquals(
        BootstrapTicketStore.Result.CONSUMED, store.consume(third, "instance"));
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
