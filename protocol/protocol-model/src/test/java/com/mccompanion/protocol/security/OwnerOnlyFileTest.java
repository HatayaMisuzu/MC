package com.mccompanion.protocol.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OwnerOnlyFileTest {
    @TempDir Path temporary;

    @Test
    void createsAndVerifiesTheNativeOwnerOnlyBoundaryBeforeWriting() throws Exception {
        Path sensitive = OwnerOnlyFile.create(temporary.resolve("sensitive.state"));
        Files.writeString(sensitive, "not-a-real-secret");
        OwnerOnlyFile.secure(sensitive);

        assertTrue(OwnerOnlyFile.isOwnerOnly(sensitive));
    }

    @Test
    void createsAndVerifiesATemporaryOwnerOnlyFile() throws Exception {
        Path sensitive = OwnerOnlyFile.createTempFile(temporary, ".sensitive-", ".state");
        Files.writeString(sensitive, "not-a-real-secret");
        OwnerOnlyFile.secure(sensitive);

        assertTrue(OwnerOnlyFile.isOwnerOnly(sensitive));
    }

    @Test
    void neverTakesOverAnExistingPathThroughTheCreationApi() throws Exception {
        Path existing = OwnerOnlyFile.create(temporary.resolve("existing.state"));

        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> OwnerOnlyFile.create(existing));
        assertTrue(OwnerOnlyFile.isOwnerOnly(existing));
    }
}
