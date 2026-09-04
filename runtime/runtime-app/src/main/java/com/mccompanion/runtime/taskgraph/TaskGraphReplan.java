package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.event.RuntimeEvent;
import com.mccompanion.runtime.json.Json;
import java.util.*;

/** Deterministic admission and rewrite invariants; never authors a plan or a goal. */
public final class TaskGraphReplan {
    public static final int MAX_REPLANS = 4, MAX_DELIVERIES_PER_EVENT = 2;
    private TaskGraphReplan() { }

    static ObjectNode initial(JsonNode provenance) {
        String goal = provenance == null ? "" : provenance.path("originalUserGoal").asText("");
        if (goal.length() > 4096) throw new IllegalArgumentException("originalUserGoal exceeds 4096 characters");
        return Json.object().put("originalGoal", goal).put("epoch", 0).put("used", 0).put("phase", "IDLE");
    }

    public static boolean semantic(RuntimeEvent event) {
        // Preserve existing emergency admission: a local-handling flag never masks these hazards.
        if (Set.of("DEATH", "FIRE", "LAVA", "LOW_AIR").contains(event.eventType())) return true;
        JsonNode payload = event.payload();
        if (payload.path("localSafetyHandling").asBoolean(false)
                || payload.path("localRecovery").asText().equals("RUNNING")
                || payload.path("localRecovery").asText().equals("RECOVERED")) return false;
        return switch (event.eventType()) {
            case "DEATH", "LOW_HEALTH", "HOSTILE_ENTERED_THREAT_RANGE", "INVENTORY_FULL",
                 "TOOL_BROKEN", "DIMENSION_CHANGED",
                 "ROUTE_INVALIDATED", "USER_INSTRUCTION" -> true;
            // Mine/collect/deposit/attack may cause these edges themselves. The executing local
            // controller verifies the effect first; its BLOCKED/FAILED edge is the escalation.
            case "CURRENT_TARGET_DIED", "TASK_TARGET_CHANGED", "TARGET_CONTAINER_CHANGED", "TARGET_BLOCK_CHANGED" ->
                    payload.path("localRecoveryExhausted").asBoolean(false);
            // These are emitted only after the existing bounded Body / Navigation recovery exits.
            case "TASK_BLOCKED", "TASK_FAILED" -> true;
            case "TASK_GRAPH_TERMINAL" -> payload.path("state").asText().equals("FAILED");
            default -> false;
        };
    }

    static boolean pending(JsonNode replan) {
        return Set.of("REQUESTED", "WAITING_BRAIN", "BLOCKED").contains(replan.path("phase").asText());
    }

    static void validateRewrite(TaskGraphExecutionRecord record, JsonNode graph) {
        requireConfirmedEffects(record);
        // Metadata, input contract, permissions and execution limits are not plan-rewrite authority.
        ObjectNode oldMeta = record.graph().deepCopy(), newMeta = graph.deepCopy();
        oldMeta.remove("root"); newMeta.remove("root");
        require(oldMeta.equals(newMeta), "REPLAN_AUTHORITY_CHANGED");
        Map<String, Node> before = index(record.graph().path("root"));
        Map<String, Node> after = index(graph.path("root"));
        Set<String> completed = new HashSet<>(), protectedNodes = new HashSet<>();
        record.completedNodes().forEach(key -> completed.add(key.asText().split("@", 2)[0]));
        protectedNodes.addAll(completed);
        record.toolResults().forEach(result -> {
            if (result.path("success").asBoolean()) protectedNodes.add(result.path("nodeId").asText());
        });
        for (String id : List.copyOf(protectedNodes)) {
            Node old = before.get(id);
            require(old != null, "REPLAN_CORRUPT_HISTORY");
            protectedNodes.addAll(old.ancestors());
        }
        for (String id : protectedNodes) {
            Node old = before.get(id), replacement = after.get(id);
            require(replacement != null && old.ancestors().equals(replacement.ancestors()),
                    "REPLAN_COMPLETED_SCOPE_CHANGED");
            if (completed.contains(id) || !old.value().path("type").asText().equals("sequence")) {
                // Partially executed loops/branches retain their control contract and iteration scopes.
                require(old.value().equals(replacement.value()), "REPLAN_COMPLETED_NODE_CHANGED");
            } else {
                ObjectNode oldControl = old.value().deepCopy(), newControl = replacement.value().deepCopy();
                oldControl.remove("nodes"); newControl.remove("nodes");
                require(oldControl.equals(newControl), "REPLAN_CONTROL_CHANGED");
            }
        }
        for (JsonNode retired : record.replan().path("retiredNodeIds"))
            require(!after.containsKey(retired.asText()), "REPLAN_RETIRED_NODE_REUSED");
        // An attempted node ID is never repurposed, even when the attempt failed/cancelled.
        record.toolResults().forEach(result -> {
            String id = result.path("nodeId").asText();
            if (after.containsKey(id)) require(before.containsKey(id)
                    && before.get(id).equals(after.get(id)), "REPLAN_ATTEMPTED_NODE_CHANGED");
        });
    }

    static void requireConfirmedEffects(TaskGraphExecutionRecord record) {
        // A cancelled child is not evidence that its earlier uncertain world effect did not happen.
        // Check persisted protocol status before cancellation can replace the last observation.
        record.toolResults().forEach(result -> require(!unconfirmed(result), "REPLAN_UNCONFIRMED_EFFECT"));
        require(!unconfirmed(record.result()), "REPLAN_UNCONFIRMED_EFFECT");
    }

    private static boolean unconfirmed(JsonNode value) {
        if (!value.isObject()) return false;
        if (value.path("reconciliationRequired").asBoolean(false)) return true;
        for (String field : List.of("code", "failureCode", "state")) {
            String status = value.path(field).asText();
            if (status.contains("UNCERTAIN") || status.contains("RECONCILIATION") || status.contains("UNCONFIRMED")) return true;
        }
        for (String field : List.of("observation", "fabricObservation", "snapshot"))
            if (unconfirmed(value.path(field))) return true;
        return false;
    }

    static Set<String> removed(JsonNode before, JsonNode after) {
        Set<String> ids = new LinkedHashSet<>(index(before.path("root")).keySet());
        ids.removeAll(index(after.path("root")).keySet());
        return ids;
    }

    private static Map<String, Node> index(JsonNode root) {
        Map<String, Node> result = new LinkedHashMap<>();
        index(root, List.of(), result); return result;
    }

    private static void index(JsonNode value, List<String> ancestors, Map<String, Node> result) {
        if (!value.isObject() || !value.has("id") || !value.has("type")) return;
        String id = value.path("id").asText();
        result.put(id, new Node(value, ancestors));
        List<String> path = new ArrayList<>(ancestors); path.add(id);
        switch (value.path("type").asText()) {
            case "sequence", "parallel", "fallback" -> value.path("nodes").forEach(n -> index(n, path, result));
            case "repeat", "while" -> index(value.path("body"), path, result);
            case "retry" -> index(value.path("node"), path, result);
            case "if" -> { index(value.path("then"), path, result); index(value.path("else"), path, result); }
            case "switch" -> {
                value.path("cases").forEach(n -> index(n.path("node"), path, result));
                index(value.path("default"), path, result);
            }
            default -> { }
        }
    }

    static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }
    private record Node(JsonNode value, List<String> ancestors) { }
}
