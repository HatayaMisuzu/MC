package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/** Stores short-lived confirmed plans and publishes structured operation progress. */
final class OperationManager implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Map<String, Plan> plans = new ConcurrentHashMap<>();
  private final Map<String, Operation> operations = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<BlockingQueue<ObjectNode>> subscribers =
      new CopyOnWriteArrayList<>();
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

  Plan create(
      String category,
      String action,
      String instanceId,
      boolean dangerous,
      JsonNode details,
      Work work) {
    String id = UUID.randomUUID().toString();
    Plan plan =
        new Plan(
            id,
            category,
            action,
            instanceId,
            dangerous,
            details.deepCopy(),
            Instant.now(),
            Instant.now().plusSeconds(300),
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
    Plan plan = plans.remove(planId);
    if (plan == null || plan.expiresAt().isBefore(Instant.now())) {
      throw new IllegalArgumentException("计划不存在或已过期，请重新生成计划");
    }
    if (!planId.equals(confirmation)) {
      throw new IllegalArgumentException("确认标识不匹配");
    }
    String operationId = UUID.randomUUID().toString();
    Operation operation =
        new Operation(
            operationId,
            plan.category(),
            plan.action(),
            plan.instanceId(),
            "QUEUED",
            0,
            "等待执行",
            null,
            null,
            Instant.now(),
            null);
    operations.put(operationId, operation);
    workers.submit(() -> run(plan, operationId));
    return operation;
  }

  Operation requireOperation(String id) {
    Operation value = operations.get(id);
    if (value == null) throw new IllegalArgumentException("操作不存在");
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
    update(id, "RUNNING", 10, "正在执行", null, null);
    try {
      JsonNode result =
          plan.work()
              .run(
                  (progress, message) ->
                      update(id, "RUNNING", Math.max(10, Math.min(95, progress)), message, null, null));
      update(id, "SUCCEEDED", 100, "执行并验证成功", result, null);
    } catch (Exception failure) {
      update(
          id,
          "FAILED",
          100,
          "执行失败，已应用业务层回滚策略",
          null,
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    }
  }

  private void update(
      String id, String state, int progress, String message, JsonNode result, String error) {
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
                message,
                result == null ? old.result() : result.deepCopy(),
                error,
                old.startedAt(),
                state.equals("SUCCEEDED") || state.equals("FAILED") ? Instant.now() : null));
    ObjectNode value = event("OPERATION_PROGRESS", id).put("state", state).put("progress", progress);
    value.put("message", message);
    if (error != null) value.put("error", error);
    publish(value);
  }

  private void publish(ObjectNode event) {
    event.put("at", Instant.now().toString());
    for (BlockingQueue<ObjectNode> queue : subscribers) {
      if (!queue.offer(event.deepCopy())) {
        queue.poll();
        queue.offer(event.deepCopy());
      }
    }
  }

  private static ObjectNode event(String type, String operationId) {
    ObjectNode value = JSON.createObjectNode().put("type", type);
    if (operationId != null) value.put("operationId", operationId);
    return value;
  }

  @Override
  public void close() {
    workers.close();
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
