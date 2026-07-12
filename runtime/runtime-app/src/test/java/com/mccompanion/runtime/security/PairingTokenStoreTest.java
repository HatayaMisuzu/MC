package com.mccompanion.runtime.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PairingTokenStoreTest {
    @TempDir Path temporary;

    @Test
    void createsAndReusesOpaqueToken() throws Exception {
        Path path = temporary.resolve("data/pairing.token");
        PairingTokenStore store = new PairingTokenStore(path);
        String first = store.loadOrCreate();
        String second = store.loadOrCreate();
        assertEquals(first, second);
        assertTrue(first.matches("[A-Za-z0-9_-]{32,128}"));
        assertEquals(first, Files.readString(path).trim());
        assertTrue(PairingTokenStore.matches(first, second));
        assertFalse(PairingTokenStore.matches(first, second + "x"));
        assertFalse(PairingTokenStore.matches(first, null));
    }

    @Test
    void rejectsMalformedExistingToken() throws Exception {
        Path path = temporary.resolve("bad.token");
        Files.writeString(path, "too-short");
        assertThrows(java.io.IOException.class, () -> new PairingTokenStore(path).loadOrCreate());
    }
}

