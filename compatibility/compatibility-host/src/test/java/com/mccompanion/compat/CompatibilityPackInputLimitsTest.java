package com.mccompanion.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CompatibilityPackInputLimitsTest {
    @TempDir Path temporary;

    @Test
    void rejectsAmbiguousManifestNamesBeforeDocumentInterpretation() throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.yaml", bytes("a: one\n"));
        entries.put("manifest.yml", bytes("a: two\n"));

        IOException failure = assertThrows(IOException.class,
                () -> new CompatibilityPackLoader().load(
                        CompatibilityPackFixture.archive(temporary.resolve("ambiguous.mcac-compat"), entries)));

        assertEquals("MANIFEST_AMBIGUOUS", failure.getMessage());
    }

    @Test
    void enforcesAggregateBudgetOnActualInflatedBytes() throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.yaml", bytes("a: value\n"));
        byte[] compressibleMegabyte = new byte[CompatibilityPackLoader.MAX_ENTRY_BYTES];
        for (int index = 0; index < 17; index++) {
            entries.put("evidence/blob-" + index + ".bin", compressibleMegabyte);
        }

        IOException failure = assertThrows(IOException.class,
                () -> new CompatibilityPackLoader().load(
                        CompatibilityPackFixture.archive(temporary.resolve("aggregate.mcac-compat"), entries)));

        assertEquals("PACK_TOTAL_UNCOMPRESSED_LIMIT", failure.getMessage());
    }

    @Test
    void rejectsDuplicateYamlKeysAndTrailingDocuments() throws Exception {
        assertDocumentInvalid("duplicate.mcac-compat", "manifest.yaml", "a: one\na: two\n");
        assertDocumentInvalid("multiple.mcac-compat", "manifest.yaml", "a: one\n---\nb: two\n");
    }

    @Test
    void rejectsDeepDocumentsHugeScalarsAndAliasExpansion() throws Exception {
        StringBuilder deep = new StringBuilder();
        for (int index = 0; index < 70; index++) {
            deep.append("  ".repeat(index)).append("a").append(index).append(":\n");
        }
        deep.append("  ".repeat(70)).append("leaf: value\n");
        assertDocumentInvalid("deep.mcac-compat", "manifest.yaml", deep.toString());

        assertDocumentInvalid("scalar.mcac-compat", "manifest.yaml",
                "value: \"" + "x".repeat(70 * 1024) + "\"\n");

        String aliases = "seed: &seed [value]\nvalues: [" + "*seed,".repeat(17) + "*seed]\n";
        assertDocumentInvalid("aliases.mcac-compat", "manifest.yaml", aliases);
    }

    @Test
    void appliesJsonDepthAndDuplicateKeyConstraints() throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("capabilities/evil.json", bytes("{\"a\":1,\"a\":2}"));
        entries.put("manifest.yaml", bytes("a: value\n"));
        Path archive = CompatibilityPackFixture.archive(temporary.resolve("json-duplicate.mcac-compat"), entries);

        IOException failure = assertThrows(IOException.class,
                () -> new CompatibilityPackLoader().load(archive));

        assertTrue(failure.getMessage().startsWith("DOCUMENT_INVALID:capabilities/evil.json"));
    }

    @Test
    void rejectsAnArchiveThatEndsBeforeItsCentralDirectory() throws Exception {
        Path complete = CompatibilityPackFixture.archive(temporary.resolve("complete.mcac-compat"),
                Map.of("manifest.yaml", bytes("a: value\n")));
        byte[] encoded = Files.readAllBytes(complete);
        Path truncated = temporary.resolve("truncated.mcac-compat");
        Files.write(truncated, Arrays.copyOf(encoded, encoded.length - 12));

        IOException failure = assertThrows(IOException.class,
                () -> new CompatibilityPackLoader().load(truncated));

        assertEquals("PACK_ZIP_INVALID", failure.getMessage());
    }

    private void assertDocumentInvalid(String archiveName, String entryName, String content) throws Exception {
        Path archive = CompatibilityPackFixture.archive(temporary.resolve(archiveName),
                Map.of(entryName, bytes(content)));
        IOException failure = assertThrows(IOException.class,
                () -> new CompatibilityPackLoader().load(archive));
        assertTrue(failure.getMessage().startsWith("DOCUMENT_INVALID:" + entryName),
                () -> "Unexpected failure: " + failure.getMessage());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
