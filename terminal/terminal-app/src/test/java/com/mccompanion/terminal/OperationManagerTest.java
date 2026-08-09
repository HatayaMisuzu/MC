package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationManagerTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void concurrentConfirmationsConsumePlanOnceAndRecoverSameOperation() throws Exception {
    try (OperationManager manager = new OperationManager()) {
      AtomicInteger sideEffects = new AtomicInteger();
      var plan = manager.create("install", "update", "instance-a", true,
          JSON.createObjectNode(), ignored -> {
            sideEffects.incrementAndGet();
            return JSON.createObjectNode();
          });
      var callers = Executors.newFixedThreadPool(12);
      try {
        var futures = IntStream.range(0, 24)
            .mapToObj(ignored -> callers.submit(() -> manager.execute(plan.id(), plan.id()).id()))
            .toList();
        var operationIds = new java.util.HashSet<String>();
        for (var future : futures) operationIds.add(future.get(2, TimeUnit.SECONDS));
        assertEquals(1, operationIds.size());
        waitForState(manager, operationIds.iterator().next(), "SUCCEEDED");
        assertEquals(1, sideEffects.get());
      } finally {
        callers.shutdownNow();
      }
    }
  }

  @Test
  void expiredPlansAndTerminalOperationsAreRemovedWithoutAffectingRecentPolling() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-26T00:00:00Z"));
    try (OperationManager manager = new OperationManager(clock)) {
      var expired =
          manager.create("runtime", "start", "instance-a", false, JSON.createObjectNode(), ignored -> JSON.createObjectNode());
      clock.advance(OperationManager.PLAN_TTL.plusSeconds(1));
      assertThrows(IllegalArgumentException.class, () -> manager.execute(expired.id(), expired.id()));

      var plan =
          manager.create("runtime", "start", "instance-a", false, JSON.createObjectNode(), ignored -> JSON.createObjectNode());
      var operation = manager.execute(plan.id(), plan.id());
      waitForState(manager, operation.id(), "SUCCEEDED");
      assertEquals("SUCCEEDED", manager.requireOperation(operation.id()).state());

      clock.advance(OperationManager.TERMINAL_OPERATION_TTL.minusSeconds(1));
      assertEquals("SUCCEEDED", manager.requireOperation(operation.id()).state());
      clock.advance(Duration.ofSeconds(2));
      assertThrows(IllegalArgumentException.class, () -> manager.requireOperation(operation.id()));
    }
  }

  @Test
  void sameInstanceOperationsSerializeWhileDifferentInstancesRemainIndependent() throws Exception {
    try (OperationManager manager = new OperationManager()) {
      CountDownLatch firstEntered = new CountDownLatch(1);
      CountDownLatch releaseFirst = new CountDownLatch(1);
      CountDownLatch sameInstanceEntered = new CountDownLatch(1);
      CountDownLatch otherInstanceEntered = new CountDownLatch(1);
      var first =
          manager.create(
              "runtime",
              "start",
              "instance-a",
              false,
              JSON.createObjectNode(),
              ignored -> {
                firstEntered.countDown();
                if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("test release was not signalled");
                }
                return JSON.createObjectNode();
              });
      var second =
          manager.create(
              "install",
              "repair",
              "instance-a",
              false,
              JSON.createObjectNode(),
              ignored -> {
                sameInstanceEntered.countDown();
                return JSON.createObjectNode();
              });
      var other =
          manager.create(
              "runtime",
              "start",
              "instance-b",
              false,
              JSON.createObjectNode(),
              ignored -> {
                otherInstanceEntered.countDown();
                return JSON.createObjectNode();
              });

      manager.execute(first.id(), first.id());
      assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
      manager.execute(second.id(), second.id());
      manager.execute(other.id(), other.id());
      assertTrue(otherInstanceEntered.await(2, TimeUnit.SECONDS));
      assertFalse(sameInstanceEntered.await(150, TimeUnit.MILLISECONDS));
      releaseFirst.countDown();
      assertTrue(sameInstanceEntered.await(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void plansResultsAndFailuresCrossTheSafeOutputBoundary() throws Exception {
    try (OperationManager manager = new OperationManager()) {
      var plan = manager.create("support", "collect", "instance-a", false,
          JSON.createObjectNode().put("path", "C:\\Users\\Alice\\secret.log"),
          ignored -> JSON.createObjectNode().put("output", "C:\\Users\\Alice\\support.zip"));
      assertFalse(plan.details().toString().contains("Alice"));
      var successful = manager.execute(plan.id(), plan.id());
      waitForState(manager, successful.id(), "SUCCEEDED");
      assertFalse(manager.requireOperation(successful.id()).result().toString().contains("Alice"));

      var failing = manager.create("runtime", "start", "instance-a", false,
          JSON.createObjectNode(), ignored -> {
            throw new IllegalStateException("token=private C:\\Users\\Alice\\runtime.log");
          });
      var failed = manager.execute(failing.id(), failing.id());
      waitForState(manager, failed.id(), "FAILED");
      String error = manager.requireOperation(failed.id()).error();
      assertTrue(error.startsWith("INTERNAL_ERROR:"));
      assertFalse(error.contains("private"));
      assertFalse(error.contains("Alice"));

      var expected = manager.create("session", "attach", "instance-a", false,
          JSON.createObjectNode(), ignored -> {
            throw new java.io.IOException("NO_ACTIVE_GAME_SESSION: no authenticated session");
          });
      var expectedFailure = manager.execute(expected.id(), expected.id());
      waitForState(manager, expectedFailure.id(), "FAILED");
      assertEquals("NO_ACTIVE_GAME_SESSION", manager.requireOperation(expectedFailure.id()).error());
    }
  }

  private static void waitForState(OperationManager manager, String id, String state) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (System.nanoTime() < deadline) {
      if (state.equals(manager.requireOperation(id).state())) return;
      Thread.sleep(10);
    }
    assertEquals(state, manager.requireOperation(id).state());
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    private MutableClock(Instant initial) {
      now = new AtomicReference<>(initial);
    }

    private void advance(Duration duration) {
      now.updateAndGet(value -> value.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC only");
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
