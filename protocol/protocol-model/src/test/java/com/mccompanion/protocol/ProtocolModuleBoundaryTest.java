package com.mccompanion.protocol;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolModuleBoundaryTest {
    private static final int JAVA_17_CLASS_MAJOR = 61;
    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "net/minecraft/",
            "net/fabricmc/",
            "net/neoforged/",
            "net/minecraftforge/",
            "com/mccompanion/core/",
            "com/mccompanion/runtime/");

    @Test
    void productionBytecodeTargetsJava17AndHasNoLayerViolations() throws Exception {
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
        Path classes = Path.of(ProtocolVersion.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
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
