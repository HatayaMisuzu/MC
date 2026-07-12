package com.mccompanion.runtime.provider;

public final class ProviderException extends Exception {
    private final String code;

    public ProviderException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ProviderException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
