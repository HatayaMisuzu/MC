package com.mccompanion.protocol;

public final class ProtocolValidationException extends RuntimeException {
    public ProtocolValidationException(String message) {
        super(message);
    }

    public ProtocolValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
