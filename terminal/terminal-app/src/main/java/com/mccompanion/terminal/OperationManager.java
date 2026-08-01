package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Stores bounded, short-lived confirmed plans and publishes structured operation progress. */
final class OperationManager implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final PrivacyFilter PRIVACY = new PrivacyFilter();
  static final int MAX_PLANS = 128;
  static final int MAX_OPERATIONS = 512;
  static final Duration PLAN_TTL = Duration.ofMinutes(5);
  static final Duration TERMINAL_OPERATION_TTL = Duration.ofMinutes(30);

  private final Map<String, Plan> plans = new ConcurrentHashMap<>();
  private final Map<String, Operation> operations = new ConcurrentHashMap<>();
  private final Map<String, ReentrantLock> instanceLocks = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<BlockingQueue<ObjectNode>> subscribers =
      new CopyOnWriteArrayList<>();
  private final ExecutorService workers;
  private final Clock clock;

  OperationManager() {
    this(Clock.systemUTC());
  }

  OperationManager(Clock clock) {
    this.clock = java.util.Objects.requireNonNull(clock, "clock");
    workers =
        new ThreadPoolExecutor(
            4,
            4,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64, true),
            runnable -> {
              Thread thread = new Thread(runnable, "mcac-terminal-operation");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  Plan create(
      String category,
      String action,
      String instanceId,
      boolean dangerous,
      JsonNode details,
      Work work) {
    cleanup();
    if (plans.size() >= MAX_PLANS) {
      plans.values().stream()
          .min(Comparator.comparing(Plan::createdAt))
          .ifPresent(value -> plans.remove(value.id(), value));
    }
    String id = UUID.randomUUID().toString();
    Instant now = clock.instant();
    JsonNode safeDetails = sanitize(details);
    Plan plan =
        new Plan(
            id,
            category,
            action,
            instanceId,
            dangerous,
            safeDetails == null ? JSON.createObjectNode() : safeDetails,
            now,
            now.plus(PLAN_TTL),
            work);
    plans.put(id, plan);
    publish(
        event("PLAN_CREATED", id)
            .put("category", category)
            .put("action", action)
            .put("instanceId", instanceId));
    return plan;
  }

  Operation execute(String planId, String confirmation) {
    cleanup();
    Plan plan = plans.get(planId);
    if (plan == null || plan.expiresAt().isBefore(clock.instant())) {
      if (plan != null) plans.remove(planId, plan);
      throw new IllegalArgumentException("Plan does not exist or has expired");
    }
    if (!planId.equals(confirmation)) {
      throw new IllegalArgumentException("Plan confirmation does not match");
    }
    if (operations.size() >= MAX_OPERATIONS) {
      throw new IllegalStateException("OPERATION_CAPACITY_REACHED");
    }
    plans.remove(planId, plan);
    String operationId = UUID.randomUUID().toString();
    Operation operation =
        new Operation(
            operationId,
            plan.category(),
            plan.action(),
            plan.instanceId(),
            "QUEUED",
            0,
            "Waiting for bounded execution capacity",
            null,
            null,
            clock.instant(),
            null);
    operations.put(operationId, operation);
    try {
      workers.execute(() -> run(plan, operationId));
    } catch (RejectedExecutionException saturated) {
      update(
          operationId,
          "FAILED",
          100,
          "Operation queue is full",
          null,
          "OPERATION_QUEUE_FULL");
    }
    return operation;
  }

  Operation requireOperation(String id) {
    cleanup();
    Operation value = operations.get(id);
    if (value == null) throw new IllegalArgumentException("Operation does not exist or has expired");
    return value;
  }

  BlockingQueue<ObjectNode> subscribe() {
    BlockingQueue<ObjectNode> queue = new LinkedBlockingQueue<>(256);
    subscribers.add(queue);
    return queue;
  }

  void unsubscribe(BlockingQueue<ObjectNode> queue) {
    subscribers.remove(queue);
  }

  void publishSystem(String type, String message) {
    publish(event(type, null).put("message", message));
  }

  private void run(Plan plan, String id) {
    ReentrantLock instanceLock =
        instanceLocks.computeIfAbsent(plan.instanceId(), ignored -> new ReentrantLock(true));
    instanceLock.lock();
    try {
      update(id, "RUNNING", 10, "Executing", null, null);
      try {
        JsonNode result =
            plan.work()
                .run(
                    (progress, message) ->
                        update(
                            id,
                            "RUNNING",
                            Math.max(10, Math.min(95, progress)),
                            message,
                            null,
                            null));
        update(id, "SUCCEEDED", 100, "Execution and verification succeeded", result, null);
      } catch (Exception failure) {
        SafeProblemMapper.Problem problem = SafeProblemMapper.unexpected(failure);
        update(
            id,
            "FAILED",
            100,
            "Execution failed; the operation-specific rollback policy was applied",
            null,
            problem.code() + ":" + problem.correlationId());
      }
    } finally {
      instanceLock.unlock();
      if (!instanceLock.isLocked() && !instanceLock.hasQueuedThreads()) {
        instanceLocks.remove(plan.instanceId(), instanceLock);
      }
    }
  }

  private void update(
      String id, String state, int progress, String message, JsonNode result, String error) {
    String safeMessage = PRIVACY.filter(message, PrivacyFilter.Policy.UI_DEFAULT);
    String safeError = PRIVACY.filter(error, PrivacyFilter.Policy.UI_DEFAULT);
    JsonNode safeResult = sanitize(result);
    operations.computeIfPresent(
        id,
        (ignored, old) ->
            new Operation(
                old.id(),
                old.category(),
                old.action(),
                old.instanceId(),
                state,
                progress,
                safeMessage,
                safeResult == null ? old.result() : safeResult,
                safeError,
                old.startedAt(),
                terminal(state) ? clock.instant() : null));
    ObjectNode value = event("OPERATION_PROGRESS", id).put("state", state).put("progress", progress);
    value.put("message", safeMessage);
    if (safeError != null) value.put("error", safeError);
    publish(value);
  }

  private static JsonNode sanitize(JsonNode value) {
    if (value == null) return null;
    if (value.isTextual()) {
      return JSON.getNodeFactory().textNode(
          PRIVACY.filter(value.asText(), PrivacyFilter.Policy.UI_DEFAULT));
    }
    if (value.isArray()) {
      var copy = JSON.createArrayNode();
      value.forEach(item -> copy.add(sanitize(item)));
      return copy;
    }
    if (value.isObject()) {
      var copy = JSON.createObjectNode();
      value.fields().forEachRemaining(entry -> copy.set(entry.getKey(), sanitize(entry.getValue())));
      return copy;
    }
    return value.deepCopy();
  }

  private void publish(ObjectNode event) {
    event.put("at", clock.instant().toString());
    for (BlockingQueue<ObjectNode> queue : subscribers) {
      if (!queue.offer(event.deepCopy())) {
        queue.poll();
        queue.offer(event.deepCopy());
      }
    }
  }

  private void cleanup() {
    Instant now = clock.instant();
    plans.values().removeIf(plan -> !plan.expiresAt().isAfter(now));
    operations.values().removeIf(
        operation ->
            operation.finishedAt() != null
                && !operation.finishedAt().plus(TERMINAL_OPERATION_TTL).isAfter(now));
  }

  private static boolean terminal(String state) {
    return state.equals("SUCCEEDED") || state.equals("FAILED") || state.equals("CANCELLED");
  }

  private static ObjectNode event(String type, String operationId) {
    ObjectNode value = JSON.createObjectNode().put("type", type);
    if (operationId != null) value.put("operationId", operationId);
    return value;
  }

  @Override
  public void close() {
    workers.shutdownNow();
    try {
      workers.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  interface Progress {
    void update(int percent, String message);
  }

  interface Work {
    JsonNode run(Progress progress) throws Exception;
  }

  record Plan(
      String id,
      String category,
      String action,
      String instanceId,
      boolean dangerous,
      JsonNode details,
      Instant createdAt,
      Instant expiresAt,
      Work work) {}

  record Operation(
      String id,
      String category,
      String action,
      String instanceId,
      String state,
      int progress,
      String message,
      JsonNode result,
      String error,
      Instant startedAt,
      Instant finishedAt) {}
}
