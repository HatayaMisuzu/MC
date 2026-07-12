package com.mccompanion.core.behavior;

public final class BehaviorTransitionException extends IllegalStateException {
    public BehaviorTransitionException(BehaviorState state, BehaviorTransition transition) {
        super("Transition " + transition + " is not valid from " + state);
    }
}
