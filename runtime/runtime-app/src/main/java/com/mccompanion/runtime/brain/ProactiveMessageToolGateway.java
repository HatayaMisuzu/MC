package com.mccompanion.runtime.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.conversation.ConversationEvent;
import com.mccompanion.runtime.conversation.ConversationService;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.security.Digests;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Lets the external Brain propose speech while Runtime deterministically enforces evidence,
 * per-mode rate limits and once-only delivery. It never decides what is worth saying.
 */
public final class ProactiveMessageToolGateway implements ToolGateway {
    private static final Set<String> EVENT_TYPES =
            Set.of("TASK_BLOCKED", "SAFETY_ALERT", "TASK_MILESTONE");
    private final BrainAuditRepository audit;
    private final ProactiveMessageRepository admissions;
    private final ConversationService conversations;

    public ProactiveMessageToolGateway(BrainAuditRepository audit,
                                       ProactiveMessageRepository admissions,
                                       ConversationService conversations) {
        this.audit = java.util.Objects.requireNonNull(audit, "audit");
        this.admissions = java.util.Objects.requireNonNull(admissions, "admissions");
        this.conversations = java.util.Objects.requireNonNull(conversations, "conversations");
    }

    @Override public List<ToolDefinition> definitions(ToolContext context) {
        ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("eventType").add("evidenceCallId").add("message");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("eventType").put("type", "string").putArray("enum")
                .add("TASK_BLOCKED").add("SAFETY_ALERT").add("TASK_MILESTONE");
        properties.putObject("evidenceCallId").put("type", "string")
                .put("minLength", 1).put("maxLength", 128);
        properties.putObject("message").put("type", "string")
                .put("minLength", 1).put("maxLength", 256);
        return List.of(new ToolDefinition("conversation.propose_proactive", "1.0",
                "Queue one evidence-bound proactive owner message under the local initiative policy",
                schema, "LOW", "COMMUNICATE", Duration.ofSeconds(5), false));
    }

    @Override public ToolResult execute(ToolContext context, ToolCall call) {
        try {
            if (!call.name().equals("conversation.propose_proactive")) {
                return ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Proactive message tool is unavailable");
            }
            JsonNode arguments = call.arguments();
            java.util.Set<String> fields = new java.util.HashSet<>();
            arguments.fieldNames().forEachRemaining(fields::add);
            if (!arguments.isObject()
                    || !Set.of("eventType", "evidenceCallId", "message").containsAll(fields)) {
                throw new IllegalArgumentException("unexpected proactive message arguments");
            }
            String eventType = text(arguments, "eventType", 32);
            String evidenceCallId = text(arguments, "evidenceCallId", 128);
            String message = text(arguments, "message", 256);
            if (!EVENT_TYPES.contains(eventType)) throw new IllegalArgumentException("eventType is unsupported");
            BrainAuditRepository.AuditedToolCall evidence =
                    audit.tool(context.brainSessionId(), evidenceCallId)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "evidenceCallId is not in the current Brain session"));
            requireMeaningful(eventType, evidence);
            BrainBehaviorSettings settings = audit.behaviorSettings(context.companionId());
            if (settings.initiativeMode() == BrainSemanticState.InitiativeMode.QUIET
                    && eventType.equals("TASK_MILESTONE")) {
                return ToolResult.rejected(call, "PROACTIVE_MODE_SUPPRESSED",
                        "QUIET mode permits only blocked-task and safety alerts");
            }
            Duration minimumInterval = switch (settings.initiativeMode()) {
                case QUIET -> Duration.ofMinutes(5);
                case NORMAL -> Duration.ofMinutes(1);
                case ACTIVE -> Duration.ofSeconds(15);
            };
            ProactiveMessageRepository.Admission admission = admissions.admit(
                    context.companionId(), context.brainSessionId(), evidenceCallId, eventType,
                    Digests.sha256(message), settings.initiativeMode().name(), minimumInterval);
            if (!admission.admitted()) {
                return ToolResult.rejected(call, admission.code(),
                        "Proactive message was suppressed by rate or deduplication policy");
            }
            ConversationEvent event = conversations.say(context.companionId(), null, "PROACTIVE", message,
                    Json.object().put("source", "external-brain")
                            .put("brainSessionId", context.brainSessionId())
                            .put("eventType", eventType)
                            .put("evidenceCallId", evidenceCallId)
                            .put("initiativeMode", settings.initiativeMode().name()));
            admissions.linkConversationEvent(admission.admissionId(), event.eventId());
            return new ToolResult(call.callId(), call.name(), true, "PROACTIVE_MESSAGE_QUEUED",
                    Json.object().put("eventId", event.eventId()).put("eventType", eventType)
                            .put("initiativeMode", settings.initiativeMode().name())
                            .put("evidenceCallId", evidenceCallId), true);
        } catch (IllegalArgumentException failure) {
            return ToolResult.rejected(call, "INVALID_TOOL_ARGUMENTS", failure.getMessage());
        } catch (SQLException failure) {
            return ToolResult.rejected(call, "PROACTIVE_PERSISTENCE_FAILED",
                    "Proactive admission could not be persisted");
        }
    }

    private static void requireMeaningful(String eventType,
                                          BrainAuditRepository.AuditedToolCall evidence) {
        ToolResult result = evidence.result();
        if (!result.terminal()) throw new IllegalArgumentException("proactive evidence is not terminal");
        String state = result.observation().path("state").asText("");
        switch (eventType) {
            case "TASK_BLOCKED" -> {
                if (!(state.equals("BLOCKED") || state.equals("PAUSED")
                        || state.equals("RECONCILIATION_REQUIRED") || state.equals("FAILED")
                        || result.code().contains("BLOCKED")
                        || result.code().contains("RECONCILIATION"))) {
                    throw new IllegalArgumentException("TASK_BLOCKED requires a blocked terminal observation");
                }
            }
            case "SAFETY_ALERT" -> {
                if (!evidence.call().name().equals("safety.inspect") || !result.success()) {
                    throw new IllegalArgumentException("SAFETY_ALERT requires successful safety.inspect evidence");
                }
            }
            case "TASK_MILESTONE" -> {
                if (!evidence.call().name().equals("task.inspect") || !result.success()
                        || state.isBlank()) {
                    throw new IllegalArgumentException(
                            "TASK_MILESTONE requires successful task.inspect state evidence");
                }
            }
            default -> throw new IllegalArgumentException("eventType is unsupported");
        }
    }

    private static String text(JsonNode value, String field, int maximum) {
        JsonNode node = value.path(field);
        if (!node.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        String result = node.asText().strip();
        if (result.isBlank() || result.length() > maximum) {
            throw new IllegalArgumentException(field + " must be 1.." + maximum + " characters");
        }
        return result;
    }
}
