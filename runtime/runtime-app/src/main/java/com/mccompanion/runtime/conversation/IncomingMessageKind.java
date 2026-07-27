package com.mccompanion.runtime.conversation;

public enum IncomingMessageKind {
    WAITING_ANSWER,
    IMMEDIATE_INSTRUCTION,
    GOAL_MODIFICATION,
    CONTROL,
    NEW_REQUEST_OR_CONVERSATION
}
