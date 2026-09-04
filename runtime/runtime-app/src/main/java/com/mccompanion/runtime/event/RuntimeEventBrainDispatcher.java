package com.mccompanion.runtime.event;

import com.mccompanion.runtime.brain.BrainContextAssembler;
import com.mccompanion.runtime.brain.BrainTurnResult;
import com.mccompanion.runtime.brain.ExternalBrainCoordinator;
import com.mccompanion.runtime.conversation.ConversationService;
import com.mccompanion.runtime.json.Json;

import java.util.Objects;

/** Delivers admitted events to the external Brain under the no-new-goal event contract. */
public final class RuntimeEventBrainDispatcher implements RuntimeEventService.Dispatcher {
    private static final String CONTROLLER_ID = "runtime-primary";
    private final ExternalBrainCoordinator brain;
    private final BrainContextAssembler contexts;
    private final ConversationService conversations;

    public RuntimeEventBrainDispatcher(ExternalBrainCoordinator brain,
                                       BrainContextAssembler contexts,
                                       ConversationService conversations) {
        this.brain = brain;
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
    }

    @Override public RuntimeEventService.DispatchResult dispatch(RuntimeEvent event) throws Exception {
        if (!wakeEligible(event)) return RuntimeEventService.DispatchResult.SUPPRESSED;
        if (brain == null) return RuntimeEventService.DispatchResult.DEFERRED;
        // Graph event deduplication MUST precede interruption. A delayed failure from the old epoch
        // must not pause the freshly resumed graph before it is recognized as stale.
        boolean graphOwnsEvent = brain.graphOwnsEvent(CONTROLLER_ID, event);
        if (event.priority() == RuntimeEvent.Priority.CRITICAL && !graphOwnsEvent) {
            brain.pauseActiveForCriticalEvent(CONTROLLER_ID, event.companionId(), event.eventType());
        }
        BrainContextAssembler.Prepared prepared;
        try {
            prepared = contexts.prepare(event.companionId());
        } catch (IllegalArgumentException missing) {
            if ("COMPANION_NOT_FOUND".equals(missing.getMessage())) {
                return RuntimeEventService.DispatchResult.SUPPRESSED;
            }
            throw missing;
        }
        // Replanning needs the connected Body tool contract. An admitted critical notification
        // outside a graph must still reach the Brain when the Body only publishes observations.
        if (graphOwnsEvent && prepared.capabilities().availableNames().isEmpty()) {
            return RuntimeEventService.DispatchResult.DEFERRED;
        }
        var result = brain.continueEvent(CONTROLLER_ID, event, prepared.context());
        if (result.kind() == BrainTurnResult.Kind.FINAL_RESPONSE && !result.response().isBlank()) {
            var details = Json.object().put("source", "runtime-event")
                    .put("runtimeEventId", event.eventId())
                    .put("eventType", event.eventType()).put("priority", event.priority().name())
                    .put("brainSessionId", result.sessionId());
            if (event.taskId() != null) details.put("taskId", event.taskId());
            if (event.taskGraphExecutionId() != null) {
                details.put("taskGraphExecutionId", event.taskGraphExecutionId());
            }
            conversations.say(event.companionId(), null, "EVENT", result.response(), details);
        } else {
            conversations.deliverPending(event.companionId());
        }
        return result.code().equals("REPLAN_PENDING") ? RuntimeEventService.DispatchResult.DEFERRED
                : result.code().equals("EVENT_NO_REPLAN") ? RuntimeEventService.DispatchResult.SUPPRESSED
                : RuntimeEventService.DispatchResult.DELIVERED;
    }

    static boolean wakeEligible(RuntimeEvent event) {
        // Authenticated Body events still pass admission/deduplication and remain inspectable.
        // Only these bounded, locally handled conditions avoid interrupting the durable task.
        if (event.payload().path("localSafetyHandling").asBoolean(false)
                && (event.category() == RuntimeEvent.Category.SURVIVAL
                    && java.util.Set.of("LOW_HEALTH", "DAMAGE", "HEALTH_RECOVERED").contains(event.eventType())
                || event.category() == RuntimeEvent.Category.PLAYER_ENTITY
                    && java.util.Set.of("HOSTILE_ENTERED_THREAT_RANGE", "CURRENT_TARGET_DIED").contains(event.eventType())))
            return false;
        if (!com.mccompanion.runtime.taskgraph.TaskGraphReplan.semantic(event)
                && !java.util.Set.of("TASK_COMPLETED", "TASK_GRAPH_TERMINAL").contains(event.eventType())) return false;
        if (event.taskBound()) return true;
        if (event.priority() != RuntimeEvent.Priority.CRITICAL) return false;
        return event.category() != RuntimeEvent.Category.PLAYER_ENTITY
                || event.eventType().equals("HOSTILE_ENTERED_THREAT_RANGE");
    }
}
