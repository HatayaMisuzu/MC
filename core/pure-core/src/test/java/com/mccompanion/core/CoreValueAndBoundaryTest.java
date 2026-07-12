package com.mccompanion.core;

import com.mccompanion.core.failure.FailureCode;
import com.mccompanion.core.id.ActionId;
import com.mccompanion.core.id.WorldId;
import com.mccompanion.core.schema.SchemaVersion;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreValueAndBoundaryTest {
    private static final int JAVA_17_CLASS_MAJOR = 61;
    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "net/minecraft/",
            "net/fabricmc/",
            "net/neoforged/",
            "net/minecraftforge/",
            "com/mccompanion/protocol/",
            "com/mccompanion/runtime/");

    @Test
    void identifiersAndSchemaVersionsHaveStableValidatedWireForms() {
        ActionId id = ActionId.random();
        assertEquals(id, ActionId.parse(id.toString()));
        assertThrows(IllegalArgumentException.class, () -> ActionId.parse("not-a-uuid"));
        assertEquals(new WorldId("world:alpha/one"), WorldId.parse("world:alpha/one"));
        assertThrows(IllegalArgumentException.class, () -> new WorldId("bad world"));

        SchemaVersion current = new SchemaVersion(3);
        assertTrue(current.canRead(new SchemaVersion(2)));
        assertTrue(current.canRead(current));
        assertFalse(current.canRead(new SchemaVersion(4)));
        assertEquals(new SchemaVersion(4), current.next());
    }

    @Test
    void requiredFailureCodesExistWithConservativeRecoveryClassification() {
        for (String required : List.of("COMPANION_NOT_FOUND", "OWNER_OFFLINE", "LEASE_EXPIRED",
                "STALE_EPOCH", "PATH_NOT_FOUND", "PATH_BLOCKED", "STUCK", "RUNTIME_OFFLINE",
                "PROVIDER_ERROR", "BEHAVIOR_TIMEOUT", "WORLD_CHANGED", "UNSUPPORTED_PLATFORM")) {
            FailureCode.valueOf(required);
        }
        assertFalse(FailureCode.UNAUTHORIZED.recoverable());
        assertTrue(FailureCode.RUNTIME_OFFLINE.recoverable());
        assertTrue(FailureCode.OK.success());
    }

    @Test
    void productionBytecodeTargetsJava17AndHasNoMinecraftOrLoaderReferences() throws Exception {
        List<Path> classFiles = productionClasses();
        assertFalse(classFiles.isEmpty(), "compiled production classes must exist");

        for (Path classFile : classFiles) {
            byte[] bytes = Files.readAllBytes(classFile);
            String constantPoolView = new String(bytes, StandardCharsets.ISO_8859_1);
            for (String forbidden : FORBIDDEN_REFERENCES) {
                assertFalse(constantPoolView.contains(forbidden),
                        () -> classFile + " references forbidden layer " + forbidden);
            }
            assertEquals(JAVA_17_CLASS_MAJOR, classMajor(classFile),
                    () -> classFile + " must remain Java 17 compatible");
        }
    }

    private static List<Path> productionClasses() throws URISyntaxException, IOException {
        Path classes = Path.of(ActionId.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "production code source must be a class directory");
        try (Stream<Path> paths = Files.walk(classes)) {
            return paths.filter(path -> path.toString().endsWith(".class")).toList();
        }
    }

    private static int classMajor(Path classFile) throws IOException {
        try (InputStream input = Files.newInputStream(classFile);
             DataInputStream data = new DataInputStream(input)) {
            assertEquals(0xCAFEBABE, data.readInt(), "class file magic");
            data.readUnsignedShort();
            return data.readUnsignedShort();
        }
    }
}
