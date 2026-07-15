package com.mccompanion.runtime.conversation;

public record IncomingMessageResolution(IncomingMessageKind kind, String optionId, String text) { }
