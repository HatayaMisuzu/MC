package com.mccompanion.protocol.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class OwnerOnlyFileTest {
    @TempDir Path temporary;

    @Test
    void appliesAndVerifiesTheNativeOwnerOnlyBoundary() throws Exception {
        Path sensitive = temporary.resolve("sensitive.state");
        Files.writeString(sensitive, "not-a-real-secret");

        OwnerOnlyFile.secure(sensitive);

        assertTrue(OwnerOnlyFile.isOwnerOnly(sensitive));
    }
}
