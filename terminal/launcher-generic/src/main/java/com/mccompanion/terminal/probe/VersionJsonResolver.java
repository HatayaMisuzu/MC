package com.mccompanion.terminal.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mccompanion.terminal.launcher.LoaderType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Safely resolves a Minecraft version JSON and its inheritsFrom chain. */
public final class VersionJsonResolver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_JSON_BYTES = 4L * 1024 * 1024;
    private static final int MAX_DEPTH = 16;

    public ResolvedVersion resolve(Path minecraftRoot, String versionId) throws IOException {
        Path versions = minecraftRoot.toRealPath().resolve("versions").normalize();
        List<JsonNode> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = validateId(versionId);
        for (int depth = 0; depth < MAX_DEPTH && current != null; depth++) {
            if (!seen.add(current)) throw new IOException("Cyclic version inheritance: " + current);
            Path file = versions.resolve(current).resolve(current + ".json").normalize();
            if (!file.startsWith(versions) || !Files.isRegularFile(file)) {
                throw new IOException("Missing version JSON: " + current);
            }
            if (Files.size(file) > MAX_JSON_BYTES) throw new IOException("Version JSON is too large: " + current);
            try (InputStream input = Files.newInputStream(file)) { chain.add(JSON.readTree(input)); }
            String inherited = chain.get(chain.size() - 1).path("inheritsFrom").asText(null);
            current = inherited == null || inherited.isBlank() ? null : validateId(inherited);
        }
        if (current != null) throw new IOException("Version inheritance exceeds " + MAX_DEPTH + " levels");
        String minecraft = firstText(chain, "clientVersion");
        if (minecraft == null) minecraft = firstText(chain, "id");
        int javaMajor = firstInt(chain, "javaVersion", "majorVersion");
        Loader loader = detectLoader(chain);
        return new ResolvedVersion(minecraft == null ? versionId : minecraft, loader.type(), loader.version(),
                javaMajor > 0 ? javaMajor : JavaRequirementResolver.requiredFor(minecraft), List.copyOf(chain));
    }

    private static Loader detectLoader(List<JsonNode> chain) {
        for (JsonNode node : chain) for (JsonNode library : node.path("libraries")) {
            String name = library.path("name").asText("");
            if (name.startsWith("net.fabricmc:fabric-loader:")) return new Loader(LoaderType.FABRIC, tail(name));
            if (name.startsWith("net.neoforged:neoforge:")) return new Loader(LoaderType.NEOFORGE, tail(name));
            if (name.startsWith("net.minecraftforge:forge:")) return new Loader(LoaderType.FORGE, tail(name));
        }
        for (JsonNode node : chain) {
            String target = node.path("arguments").path("game").toString().toLowerCase();
            if (target.contains("forgeclient")) return new Loader(LoaderType.FORGE, "unknown");
        }
        return new Loader(LoaderType.VANILLA, "");
    }

    private static String firstText(List<JsonNode> chain, String field) {
        for (JsonNode node : chain) if (node.hasNonNull(field) && !node.path(field).asText().isBlank()) return node.path(field).asText();
        return null;
    }
    private static int firstInt(List<JsonNode> chain, String parent, String field) {
        for (JsonNode node : chain) if (node.path(parent).path(field).canConvertToInt()) return node.path(parent).path(field).asInt();
        return 0;
    }
    private static String tail(String coordinate) { return coordinate.substring(coordinate.lastIndexOf(':') + 1); }
    private static String validateId(String value) throws IOException {
        if (value == null || value.isBlank() || value.contains("..") || value.contains("/") || value.contains("\\"))
            throw new IOException("Unsafe version id");
        return value;
    }
    private record Loader(LoaderType type, String version) {}
    public record ResolvedVersion(String minecraftVersion, LoaderType loader, String loaderVersion,
                                  int requiredJavaMajor, List<JsonNode> jsonChain) {}
}
