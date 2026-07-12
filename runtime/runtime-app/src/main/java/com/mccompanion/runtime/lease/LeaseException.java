package com.mccompanion.runtime.lease;

public final class LeaseException extends RuntimeException {
    private final String code;

    public LeaseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
