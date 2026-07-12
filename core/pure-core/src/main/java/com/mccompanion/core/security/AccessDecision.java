package com.mccompanion.core.security;

import com.mccompanion.core.failure.FailureCode;

import java.util.Objects;

public record AccessDecision(boolean allowed, FailureCode failureCode, String message) {
    public AccessDecision {
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (allowed != failureCode.success()) {
            throw new IllegalArgumentException("allowed must agree with failureCode");
        }
    }

    public static AccessDecision allow(String message) {
        return new AccessDecision(true, FailureCode.OK, message);
    }

    public static AccessDecision deny(String message) {
        return new AccessDecision(false, FailureCode.UNAUTHORIZED, message);
    }
}
