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
        if (brain == null) return RuntimeEventService.DispatchResult.DEFERRED;
        if (!event.taskBound() && event.priority() != RuntimeEvent.Priority.CRITICAL) {
            return RuntimeEventService.DispatchResult.SUPPRESSED;
        }
        if (event.priority() == RuntimeEvent.Priority.CRITICAL) {
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
        return RuntimeEventService.DispatchResult.DELIVERED;
    }
}
