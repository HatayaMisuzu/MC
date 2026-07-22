package com.mccompanion.runtime.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.json.Json;

import java.util.LinkedHashSet;
import java.util.List;

/** Deterministic bounded projection; it summarizes lifecycle data but never invents facts. */
public final class BoundedBrainContextAssembler {
    public static final int DEFAULT_TOTAL_CHARS = 40_000;
    private static final int WORLD_CHARS = 12_000;
    private static final int CONVERSATION_CHARS = 10_000;
    private static final int TASK_CHARS = 8_000;
    private static final int MEMORY_CHARS = 6_000;
    private static final int CAPSULE_CHARS = 6_000;

    private BoundedBrainContextAssembler() { }

    public static ObjectNode budgetSummary() {
        return Json.object().put("totalChars", DEFAULT_TOTAL_CHARS).put("worldChars", WORLD_CHARS)
                .put("conversationChars", CONVERSATION_CHARS).put("taskChars", TASK_CHARS)
                .put("approvedMemoryChars", MEMORY_CHARS).put("episodeCapsuleChars", CAPSULE_CHARS)
                .put("fullGraphIncluded", false).put("fullToolLogIncluded", false)
                .put("fullSearchPageIncluded", false);
    }

    public static Result assemble(AgentContext context) {
        ObjectNode value = Json.object().put("companionId", boundedText(context.companionId(), 128))
                .put("maxPlanSteps", context.maxPlanSteps());
        ObjectNode stats = Json.object();
        value.set("verifiedWorld", bounded(context.verifiedWorld(), WORLD_CHARS, stats, "verifiedWorld"));
        value.set("activeTask", bounded(context.activeTask(), TASK_CHARS, stats, "activeTask"));
        value.set("preferences", bounded(context.preferences(), MEMORY_CHARS, stats, "approvedMemory"));
        value.set("episodeCapsule", bounded(context.episodeCapsule(), CAPSULE_CHARS, stats, "episodeCapsule"));
        value.set("recentConversation", boundedStrings(context.recentConversation(), 16,
                CONVERSATION_CHARS, stats, "recentConversation"));
        value.set("knownLandmarks", boundedStrings(context.knownLandmarks(), 64, 4_000, stats, "knownLandmarks"));
        value.set("availableCapabilities", boundedStrings(context.availableCapabilities(), 64, 4_000,
                stats, "availableCapabilities"));
        int original = Json.write(value).length();
        if (original > DEFAULT_TOTAL_CHARS) {
            value.set("verifiedWorld", bounded(context.verifiedWorld(), 4_000, stats, "verifiedWorldTotal"));
            value.set("recentConversation", boundedStrings(context.recentConversation(), 8, 4_000,
                    stats, "recentConversationTotal"));
        }
        int emitted = Json.write(value).length();
        stats.put("budgetChars", DEFAULT_TOTAL_CHARS).put("emittedChars", emitted)
                .put("totalClipped", original > DEFAULT_TOTAL_CHARS);
        return new Result(value, stats);
    }

    public static JsonNode bounded(JsonNode input, int maxChars, ObjectNode stats, String category) {
        if (input == null || input.isNull() || input.isMissingNode()) return Json.object();
        if (Json.write(input).length() <= maxChars) return input.deepCopy();
        stats.put(category + "Clipped", true);
        if (input.isObject()) {
            ObjectNode output = Json.object();
            input.fields().forEachRemaining(entry -> {
                if (output.size() >= 64 || Json.write(output).length() >= maxChars - 512) return;
                JsonNode candidate = scalar(entry.getValue()) ? clippedScalar(entry.getValue())
                        : bounded(entry.getValue(), Math.max(256, maxChars / 4), stats, category + "." + entry.getKey());
                output.set(entry.getKey(), candidate);
            });
            output.put("_truncated", true);
            return output;
        }
        if (input.isArray()) {
            ArrayNode output = Json.MAPPER.createArrayNode();
            int start = Math.max(0, input.size() - 64);
            for (int index = start; index < input.size() && Json.write(output).length() < maxChars - 512; index++) {
                JsonNode item = input.path(index);
                output.add(scalar(item) ? clippedScalar(item)
                        : bounded(item, Math.max(256, maxChars / 8), stats, category + "[]"));
            }
            return output;
        }
        return clippedScalar(input);
    }

    private static ArrayNode boundedStrings(List<String> source, int maximum, int maxChars,
                                            ObjectNode stats, String category) {
        var unique = new LinkedHashSet<String>();
        if (source != null) for (int index = source.size() - 1; index >= 0 && unique.size() < maximum; index--) {
            String value = source.get(index);
            if (value != null && !value.isBlank()) unique.add(boundedText(value, 1_024));
        }
        List<String> newestFirst = unique.stream().toList();
        ArrayNode result = Json.MAPPER.createArrayNode();
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            String value = newestFirst.get(index);
            if (Json.write(result).length() + value.length() > maxChars) {
                stats.put(category + "Clipped", true);
                break;
            }
            result.add(value);
        }
        if (source != null && result.size() < source.size()) stats.put(category + "Dropped", source.size() - result.size());
        return result;
    }

    private static boolean scalar(JsonNode value) {
        return value == null || value.isValueNode();
    }

    private static JsonNode clippedScalar(JsonNode value) {
        if (value != null && value.isTextual()) return Json.MAPPER.getNodeFactory().textNode(boundedText(value.asText(), 1_024));
        return value == null ? Json.MAPPER.nullNode() : value.deepCopy();
    }

    private static String boundedText(String value, int maximum) {
        String text = value == null ? "" : value;
        return text.length() <= maximum ? text : text.substring(0, maximum) + "…[clipped]";
    }

    public record Result(ObjectNode context, ObjectNode clippingStats) { }
}
