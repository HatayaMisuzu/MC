package com.mccompanion.runtime.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.capability.CapabilityVisibility;
import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.conversation.ConversationService;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.memory.MemoryRepository;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.SessionRegistry;

import java.sql.SQLException;
import java.util.Objects;

/** Builds the same bounded verified context for player turns and event-driven turns. */
public final class BrainContextAssembler {
    private final CompanionRepository companions;
    private final SessionRegistry sessions;
    private final CapabilityVisibility capabilityVisibility;
    private final MemoryRepository memories;
    private final ConversationService conversations;
    private final CommandService commands;

    public BrainContextAssembler(CompanionRepository companions, SessionRegistry sessions,
                                 CapabilityVisibility capabilityVisibility,
                                 MemoryRepository memories, ConversationService conversations,
                                 CommandService commands) {
        this.companions = Objects.requireNonNull(companions, "companions");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.capabilityVisibility = Objects.requireNonNull(capabilityVisibility, "capabilityVisibility");
        this.memories = Objects.requireNonNull(memories, "memories");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    public Prepared prepare(String companionId) throws SQLException {
        var companion = companions.get(companionId)
                .orElseThrow(() -> new IllegalArgumentException("COMPANION_NOT_FOUND"));
        var session = sessions.forCompanion(companionId).orElse(null);
        CapabilityVisibility.Snapshot visible = capabilityVisibility.resolve(
                session == null ? null : session.handshake(), companion.status());
        JsonNode verifiedWorld = memories.enrichVerifiedWorld(companionId, companion.status());
        JsonNode activeTask = commands.activeTaskFor(companionId)
                .<JsonNode>map(Json.MAPPER::valueToTree).orElseGet(Json::object);
        AgentContext context = new AgentContext(companionId, verifiedWorld,
                conversations.recentTranscript(companionId, 12), activeTask,
                memories.verifiedLandmarkKeys(companionId), visible.availableNames(),
                memories.preferenceContext(companionId, 24),
                memories.latestCapsuleContext(companionId), 5);
        return new Prepared(context, visible);
    }

    public record Prepared(AgentContext context, CapabilityVisibility.Snapshot capabilities) { }
}
