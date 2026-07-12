package com.mccompanion.core.action;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public record InventoryDigest(
        String algorithm,
        String digest,
        int occupiedSlots,
        long itemCount) {

    public static final String SHA_256 = "SHA-256";

    public InventoryDigest {
        Objects.requireNonNull(algorithm, "algorithm");
        if (!SHA_256.equals(algorithm)) {
            throw new IllegalArgumentException("only SHA-256 inventory digests are supported");
        }
        Objects.requireNonNull(digest, "digest");
        digest = digest.toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest must be a 64-character SHA-256 hex value");
        }
        if (occupiedSlots < 0 || itemCount < 0) {
            throw new IllegalArgumentException("inventory counts must be non-negative");
        }
        if (itemCount < occupiedSlots) {
            throw new IllegalArgumentException("itemCount cannot be smaller than occupiedSlots");
        }
    }

    public static InventoryDigest sha256(byte[] canonicalInventory, int occupiedSlots, long itemCount) {
        Objects.requireNonNull(canonicalInventory, "canonicalInventory");
        try {
            byte[] hash = MessageDigest.getInstance(SHA_256).digest(canonicalInventory);
            return new InventoryDigest(SHA_256, HexFormat.of().formatHex(hash), occupiedSlots, itemCount);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
