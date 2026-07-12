package com.mccompanion.core.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public record CommandFingerprint(String value) {
    public CommandFingerprint {
        Objects.requireNonNull(value, "value");
        value = value.toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("command fingerprint must be a SHA-256 hex value");
        }
    }

    public static CommandFingerprint sha256(byte[] canonicalCommand) {
        Objects.requireNonNull(canonicalCommand, "canonicalCommand");
        try {
            return new CommandFingerprint(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalCommand)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
