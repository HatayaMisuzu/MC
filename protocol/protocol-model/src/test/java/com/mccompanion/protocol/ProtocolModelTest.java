package com.mccompanion.protocol;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolModelTest {
    private static final Instant NOW = Instant.parse("2026-07-12T00:00:00Z");

    @Test
    void parsesAndNegotiatesCompatibleProtocolVersions() {
        ProtocolVersion local = new ProtocolVersion("mc-companion", 1, 4);
        ProtocolNegotiator negotiator = new ProtocolNegotiator(local);

        ProtocolNegotiationResult accepted = negotiator.negotiate(ProtocolVersion.parse("mc-companion/1.2"));
        ProtocolNegotiationResult wrongMajor = negotiator.negotiate(ProtocolVersion.parse("mc-companion/2"));
        ProtocolNegotiationResult wrongProduct = negotiator.negotiate(ProtocolVersion.parse("other-product/1"));

        assertTrue(accepted.accepted());
        assertEquals(new ProtocolVersion("mc-companion", 1, 2), accepted.negotiatedVersion());
        assertEquals(ProtocolRejectionReason.UNSUPPORTED_MAJOR, wrongMajor.rejectionReason());
        assertEquals(ProtocolRejectionReason.WRONG_PRODUCT, wrongProduct.rejectionReason());
        assertTrue(wrongMajor.message().contains("local-only"));
        assertThrows(IllegalArgumentException.class, () -> ProtocolVersion.parse("mc-companion"));
        assertThrows(IllegalArgumentException.class, () -> ProtocolVersion.parse("MC/1"));
    }

    @Test
    void sequenceTrackerRejectsDuplicatesAndReordering() {
        ProtocolSequenceTracker tracker = new ProtocolSequenceTracker();

        assertTrue(tracker.accept(0));
        assertTrue(tracker.accept(3));
        assertFalse(tracker.accept(3));
        assertFalse(tracker.accept(2));
        assertFalse(tracker.accept(-1));
        assertEquals(3, tracker.lastAccepted());
    }

    @Test
    void behaviorEventsRequireEventAndStateToAgree() {
        BehaviorEvent completed = event(BehaviorEventType.COMPLETED, ProtocolBehaviorState.COMPLETED,
                null, null);
        assertTrue(completed.state().terminal());

        assertThrows(IllegalArgumentException.class,
                () -> event(BehaviorEventType.COMPLETED, ProtocolBehaviorState.RUNNING, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> event(BehaviorEventType.PROGRESS, ProtocolBehaviorState.FAILED,
                        "PATH_BLOCKED", "blocked"));
        assertThrows(NullPointerException.class,
                () -> event(BehaviorEventType.FAILED, ProtocolBehaviorState.FAILED, null, "blocked"));

        BehaviorEvent failed = event(BehaviorEventType.FAILED, ProtocolBehaviorState.FAILED,
                "PATH_BLOCKED", "blocked");
        assertEquals("PATH_BLOCKED", failed.failureCode());
    }

    @Test
    void capabilitySetIsImmutableSortedAndReportsAvailability() {
        CapabilitySet set = CapabilitySet.builder()
                .unavailable("z-adapter", "not installed")
                .available("movement", "1")
                .build();

        assertEquals(java.util.List.of("movement", "z-adapter"), java.util.List.copyOf(set.names()));
        assertTrue(set.isAvailable("movement"));
        assertFalse(set.isAvailable("z-adapter"));
        assertFalse(set.isAvailable("missing"));
        assertThrows(UnsupportedOperationException.class,
                () -> set.asMap().put("other", CapabilityDescriptor.available("1")));
        assertThrows(IllegalArgumentException.class,
                () -> CapabilitySet.builder().available("movement", "1").available("movement", "2"));
    }

    @Test
    void handshakeTokenRejectsWhitespaceAndControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> handshake("short"));
        assertThrows(IllegalArgumentException.class, () -> handshake("0123456789abcde\n"));
        assertThrows(IllegalArgumentException.class, () -> handshake("0123456789abcde "));
        assertEquals("0123456789abcdef", handshake("0123456789abcdef").token());
    }

    @Test
    void statusRequiresCoherentBehaviorTuple() {
        CompanionStatus idle = status(null, null, 0);
        assertEquals(0, idle.behaviorRevision());

        assertThrows(IllegalArgumentException.class,
                () -> status(null, ProtocolBehaviorState.RUNNING, 0));
        assertThrows(IllegalArgumentException.class,
                () -> status(null, null, 1));
        assertThrows(NullPointerException.class,
                () -> status("behavior-1", null, 1));
        assertEquals(ProtocolBehaviorState.RUNNING,
                status("behavior-1", ProtocolBehaviorState.RUNNING, 1).behaviorState());
    }

    @Test
    void handshakeResponseCannotMixAcceptedAndRejectedFields() {
        HandshakeResponse accepted = HandshakeResponse.accepted(ProtocolVersion.CURRENT,
                "session-1", "0.1.0-alpha", ControlPolicy.safeDefaults());
        assertTrue(accepted.accepted());

        assertThrows(IllegalArgumentException.class, () -> new HandshakeResponse(true,
                ProtocolVersion.CURRENT, "session-1", "0.1.0-alpha", ControlPolicy.safeDefaults(),
                "INVALID_REQUEST", null));
        assertThrows(IllegalArgumentException.class, () -> new HandshakeResponse(false,
                ProtocolVersion.CURRENT, "session-1", null, null, "INVALID_REQUEST", "rejected"));
    }

    @Test
    void commandLeaseFieldsAreValidatedByCommandType() {
        assertThrows(IllegalArgumentException.class, () -> command(
                CommandType.START_BEHAVIOR, "task-1", null, 0));
        assertThrows(IllegalArgumentException.class, () -> command(
                CommandType.START_BEHAVIOR, null, "lease-1", 1));
        assertThrows(IllegalArgumentException.class, () -> command(
                CommandType.QUERY_STATUS, null, "lease-1", 1));

        CommandEnvelope start = command(CommandType.START_BEHAVIOR, "task-1", "lease-1", 1);
        CommandEnvelope acquire = command(CommandType.ACQUIRE_LEASE, null, null, 0);
        CommandEnvelope registryQuery = command(CommandType.QUERY_REGISTRY, null, null, 0);
        CommandEnvelope recipeQuery = command(CommandType.QUERY_RECIPE, null, null, 0);
        CommandEnvelope observationQuery = command(CommandType.QUERY_OBSERVATION, null, null, 0);
        assertEquals(1, start.controlEpoch());
        assertEquals(0, acquire.controlEpoch());
        assertEquals(CommandType.QUERY_REGISTRY, registryQuery.command());
        assertEquals(CommandType.QUERY_RECIPE, recipeQuery.command());
        assertEquals(CommandType.QUERY_OBSERVATION, observationQuery.command());
    }

    private static HandshakeRequest handshake(String token) {
        return new HandshakeRequest(ProtocolVersion.CURRENT, "0.1.0-alpha", "1.21.1",
                PlatformLoader.FABRIC, "world-1", CapabilitySet.empty(), token);
    }

    private static CompanionStatus status(
            String behaviorId,
            ProtocolBehaviorState behaviorState,
            long revision) {
        return new CompanionStatus("companion-1", "owner-1", "Alex", "world-1",
                "minecraft:overworld", new PositionDto(1, 64, 2), CompanionBodyState.SPAWNED,
                behaviorId, behaviorState, revision, 0, false, CapabilitySet.empty(), NOW);
    }

    private static BehaviorEvent event(
            BehaviorEventType type,
            ProtocolBehaviorState state,
            String failureCode,
            String message) {
        return new BehaviorEvent("event-1", "behavior-1", "command-1", "companion-1",
                type, state, 1, 20, type == BehaviorEventType.COMPLETED ? 1.0 : 0.5,
                failureCode, message, NOW, JsonNodeFactory.instance.objectNode());
    }

    private static CommandEnvelope command(
            CommandType type,
            String taskId,
            String leaseId,
            long epoch) {
        return new CommandEnvelope("command-1", type, "companion-1", taskId, leaseId,
                epoch, 0, Map.of());
    }
}
