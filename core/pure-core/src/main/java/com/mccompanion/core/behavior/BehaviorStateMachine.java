package com.mccompanion.core.behavior;

import com.mccompanion.core.failure.FailureCode;
import com.mccompanion.core.id.BehaviorId;
import com.mccompanion.core.id.CompanionId;
import com.mccompanion.core.id.TaskId;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class BehaviorStateMachine {
    private final Clock clock;
    private BehaviorSnapshot snapshot;

    private BehaviorStateMachine(Clock clock, BehaviorSnapshot snapshot) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public static BehaviorStateMachine create(
            BehaviorId behaviorId,
            TaskId taskId,
            CompanionId companionId,
            String behaviorType,
            long createdTick,
            Clock clock) {
        Objects.requireNonNull(clock, "clock");
        Instant now = clock.instant();
        BehaviorSnapshot initial = new BehaviorSnapshot(behaviorId, taskId, companionId, behaviorType,
                BehaviorState.CREATED, BehaviorRevision.INITIAL, createdTick, createdTick,
                now, now, null, null, FailureCode.OK, null);
        return new BehaviorStateMachine(clock, initial);
    }

    public static BehaviorStateMachine restore(BehaviorSnapshot snapshot, Clock clock) {
        return new BehaviorStateMachine(clock, snapshot);
    }

    public synchronized BehaviorSnapshot start(long tick) {
        return apply(BehaviorTransition.START_REQUESTED, tick, FailureCode.OK, null);
    }

    public synchronized BehaviorSnapshot markRunning(long tick) {
        return apply(BehaviorTransition.START_CONFIRMED, tick, FailureCode.OK, null);
    }

    public synchronized BehaviorSnapshot progress(long tick, String detail) {
        return apply(BehaviorTransition.PROGRESS, tick, FailureCode.OK, detail);
    }

    public synchronized BehaviorSnapshot waitFor(long tick, String reason) {
        return apply(BehaviorTransition.WAIT, tick, FailureCode.OK, requireDetail(reason));
    }

    public synchronized BehaviorSnapshot ready(long tick) {
        return apply(BehaviorTransition.READY, tick, FailureCode.OK, null);
    }

    public synchronized BehaviorSnapshot pause(long tick, String reason) {
        return apply(BehaviorTransition.PAUSE, tick, FailureCode.OK, reason == null ? null : requireDetail(reason));
    }

    public synchronized BehaviorSnapshot resume(long tick) {
        return apply(BehaviorTransition.RESUME, tick, FailureCode.OK, null);
    }

    public synchronized BehaviorSnapshot block(long tick, String reason) {
        return apply(BehaviorTransition.BLOCK, tick, FailureCode.OK, requireDetail(reason));
    }

    public synchronized BehaviorSnapshot unblock(long tick) {
        return apply(BehaviorTransition.UNBLOCK, tick, FailureCode.OK, null);
    }

    public synchronized BehaviorSnapshot complete(long tick) {
        return apply(BehaviorTransition.COMPLETE, tick, FailureCode.OK, null);
    }

    public synchronized BehaviorSnapshot fail(long tick, FailureCode failureCode, String message) {
        Objects.requireNonNull(failureCode, "failureCode");
        if (failureCode == FailureCode.OK || failureCode == FailureCode.BEHAVIOR_CANCELLED) {
            throw new IllegalArgumentException("fail requires a non-cancellation failure code");
        }
        return apply(BehaviorTransition.FAIL, tick, failureCode, requireDetail(message));
    }

    public synchronized BehaviorSnapshot cancel(long tick, String reason) {
        return apply(BehaviorTransition.CANCEL, tick, FailureCode.BEHAVIOR_CANCELLED,
                reason == null ? "Behavior cancelled" : requireDetail(reason));
    }

    public synchronized BehaviorSnapshot apply(
            BehaviorTransition transition,
            long tick,
            FailureCode failureCode,
            String detail) {
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(failureCode, "failureCode");
        validateTransitionArguments(transition, failureCode, detail);
        if (tick < snapshot.lastTick()) {
            throw new IllegalArgumentException("tick cannot move backwards");
        }
        BehaviorState current = snapshot.state();
        BehaviorState next = nextState(current, transition);
        BehaviorState pausedFrom = transition == BehaviorTransition.PAUSE ? current
                : next == BehaviorState.PAUSED ? snapshot.pausedFrom() : null;
        if (transition == BehaviorTransition.RESUME) {
            next = snapshot.pausedFrom();
            pausedFrom = null;
        }

        Instant now = clock.instant();
        if (now.isBefore(snapshot.updatedAt())) {
            throw new IllegalStateException("clock moved backwards");
        }
        Instant endedAt = next.terminal() ? now : null;
        FailureCode storedFailure = next == BehaviorState.CANCELLED
                ? FailureCode.BEHAVIOR_CANCELLED
                : next == BehaviorState.FAILED ? failureCode : FailureCode.OK;
        snapshot = new BehaviorSnapshot(snapshot.behaviorId(), snapshot.taskId(), snapshot.companionId(),
                snapshot.behaviorType(), next, snapshot.revision().next(), snapshot.createdTick(), tick,
                snapshot.createdAt(), now, endedAt, pausedFrom, storedFailure, detail);
        return snapshot;
    }

    public synchronized BehaviorSnapshot snapshot() {
        return snapshot;
    }

    private static BehaviorState nextState(BehaviorState current, BehaviorTransition transition) {
        if (current.terminal()) {
            throw new BehaviorTransitionException(current, transition);
        }
        if (transition == BehaviorTransition.CANCEL) {
            return BehaviorState.CANCELLED;
        }
        if (transition == BehaviorTransition.FAIL) {
            return BehaviorState.FAILED;
        }
        if (transition == BehaviorTransition.PAUSE && current.active() && current != BehaviorState.PAUSED) {
            return BehaviorState.PAUSED;
        }
        if (current == BehaviorState.CREATED && transition == BehaviorTransition.START_REQUESTED) {
            return BehaviorState.STARTING;
        }
        if (current == BehaviorState.STARTING && transition == BehaviorTransition.START_CONFIRMED) {
            return BehaviorState.RUNNING;
        }
        if (current == BehaviorState.RUNNING && transition == BehaviorTransition.PROGRESS) {
            return BehaviorState.RUNNING;
        }
        if (current == BehaviorState.RUNNING && transition == BehaviorTransition.WAIT) {
            return BehaviorState.WAITING;
        }
        if (current == BehaviorState.WAITING && transition == BehaviorTransition.READY) {
            return BehaviorState.RUNNING;
        }
        if ((current == BehaviorState.RUNNING || current == BehaviorState.WAITING)
                && transition == BehaviorTransition.BLOCK) {
            return BehaviorState.BLOCKED;
        }
        if (current == BehaviorState.BLOCKED && transition == BehaviorTransition.UNBLOCK) {
            return BehaviorState.RUNNING;
        }
        if (current == BehaviorState.PAUSED && transition == BehaviorTransition.RESUME) {
            return BehaviorState.PAUSED;
        }
        if ((current == BehaviorState.RUNNING || current == BehaviorState.WAITING
                || current == BehaviorState.BLOCKED) && transition == BehaviorTransition.COMPLETE) {
            return BehaviorState.COMPLETED;
        }
        throw new BehaviorTransitionException(current, transition);
    }

    private static String requireDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        return detail;
    }

    private static void validateTransitionArguments(
            BehaviorTransition transition,
            FailureCode failureCode,
            String detail) {
        if (transition == BehaviorTransition.FAIL) {
            if (failureCode == FailureCode.OK || failureCode == FailureCode.BEHAVIOR_CANCELLED) {
                throw new IllegalArgumentException("FAIL requires a non-cancellation failure code");
            }
            requireDetail(detail);
            return;
        }
        if (transition == BehaviorTransition.CANCEL) {
            if (failureCode != FailureCode.BEHAVIOR_CANCELLED) {
                throw new IllegalArgumentException("CANCEL requires BEHAVIOR_CANCELLED");
            }
            requireDetail(detail);
            return;
        }
        if (failureCode != FailureCode.OK) {
            throw new IllegalArgumentException("only FAIL and CANCEL may carry a failure code");
        }
        if (transition == BehaviorTransition.WAIT || transition == BehaviorTransition.BLOCK) {
            requireDetail(detail);
        }
    }
}
