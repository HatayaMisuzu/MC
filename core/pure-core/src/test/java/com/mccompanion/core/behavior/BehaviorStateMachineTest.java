package com.mccompanion.core.behavior;

import com.mccompanion.core.failure.FailureCode;
import com.mccompanion.core.id.BehaviorId;
import com.mccompanion.core.id.CompanionId;
import com.mccompanion.core.id.TaskId;
import com.mccompanion.core.testutil.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorStateMachineTest {
    private static final Instant START = Instant.parse("2026-07-12T00:00:00Z");

    @Test
    void executesLifecycleAndRestoresPausedWaitingState() {
        MutableClock clock = new MutableClock(START);
        BehaviorStateMachine machine = create(clock);

        assertEquals(BehaviorState.CREATED, machine.snapshot().state());
        assertEquals(BehaviorState.STARTING, machine.start(11).state());
        clock.advance(Duration.ofMillis(50));
        assertEquals(BehaviorState.RUNNING, machine.markRunning(12).state());
        assertEquals(BehaviorState.WAITING, machine.waitFor(13, "owner offline").state());

        BehaviorSnapshot paused = machine.pause(14, "runtime disconnected");
        assertEquals(BehaviorState.PAUSED, paused.state());
        assertEquals(BehaviorState.WAITING, paused.pausedFrom());

        BehaviorSnapshot resumed = machine.resume(15);
        assertEquals(BehaviorState.WAITING, resumed.state());
        assertNull(resumed.pausedFrom());
        assertEquals(BehaviorState.RUNNING, machine.ready(16).state());
        assertEquals(BehaviorState.BLOCKED, machine.block(17, "path unavailable").state());
        assertEquals(BehaviorState.RUNNING, machine.unblock(18).state());

        BehaviorSnapshot completed = machine.complete(19);
        assertEquals(BehaviorState.COMPLETED, completed.state());
        assertTrue(completed.terminal());
        assertEquals(FailureCode.OK, completed.failureCode());
        assertEquals(9, completed.revision().value());
        assertThrows(BehaviorTransitionException.class, () -> machine.cancel(20, "too late"));
    }

    @Test
    void pauseAndResumePreserveBlockedAndStartingStates() {
        MutableClock clock = new MutableClock(START);
        BehaviorStateMachine starting = create(clock);
        starting.start(10);
        starting.pause(11, null);
        assertEquals(BehaviorState.STARTING, starting.resume(12).state());

        BehaviorStateMachine blocked = create(clock);
        blocked.start(10);
        blocked.markRunning(11);
        blocked.block(12, "blocked");
        blocked.pause(13, "operator pause");
        assertEquals(BehaviorState.BLOCKED, blocked.resume(14).state());
    }

    @Test
    void failureAndCancellationCarryStructuredTerminalState() {
        MutableClock clock = new MutableClock(START);
        BehaviorStateMachine failed = create(clock);
        failed.start(10);
        failed.markRunning(11);
        BehaviorSnapshot failure = failed.fail(12, FailureCode.PATH_NOT_FOUND, "no route");
        assertEquals(BehaviorState.FAILED, failure.state());
        assertEquals(FailureCode.PATH_NOT_FOUND, failure.failureCode());
        assertEquals("no route", failure.statusDetail());

        BehaviorStateMachine cancelled = create(clock);
        BehaviorSnapshot cancellation = cancelled.cancel(10, null);
        assertEquals(BehaviorState.CANCELLED, cancellation.state());
        assertEquals(FailureCode.BEHAVIOR_CANCELLED, cancellation.failureCode());
        assertFalse(cancellation.failureCode().recoverable());
    }

    @Test
    void rejectsInvalidTransitionsTicksFailureArgumentsAndClockRollback() {
        MutableClock clock = new MutableClock(START);
        BehaviorStateMachine machine = create(clock);

        assertThrows(BehaviorTransitionException.class, () -> machine.markRunning(10));
        machine.start(10);
        assertThrows(IllegalArgumentException.class,
                () -> machine.progress(9, "tick moved backward"));
        assertThrows(IllegalArgumentException.class,
                () -> machine.apply(BehaviorTransition.FAIL, 11, FailureCode.OK, "bad"));
        assertThrows(IllegalArgumentException.class,
                () -> machine.apply(BehaviorTransition.CANCEL, 11, FailureCode.OK, "cancel"));
        assertThrows(IllegalArgumentException.class,
                () -> machine.apply(BehaviorTransition.WAIT, 11, FailureCode.OK, " "));

        clock.set(START.minusSeconds(1));
        assertThrows(IllegalStateException.class, () -> machine.markRunning(11));
    }

    @Test
    void restoresValidatedSnapshotAndContinuesRevision() {
        MutableClock clock = new MutableClock(START);
        BehaviorStateMachine machine = create(clock);
        machine.start(10);
        BehaviorSnapshot snapshot = machine.markRunning(11);

        BehaviorStateMachine restored = BehaviorStateMachine.restore(snapshot, clock);
        BehaviorSnapshot progressed = restored.progress(12, "moving");

        assertEquals(snapshot.revision().next(), progressed.revision());
        assertEquals("moving", progressed.statusDetail());
    }

    private static BehaviorStateMachine create(MutableClock clock) {
        return BehaviorStateMachine.create(BehaviorId.random(), TaskId.random(), CompanionId.random(),
                "follow_owner", 10, clock);
    }
}
