package com.mccompanion.terminal.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Metadata-only JAR inspection with conservative zip-bomb limits. */
public final class ModJarInspector {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ENTRIES = 20_000;
    private static final long MAX_ENTRY = 2L * 1024 * 1024;

    public List<ModInfo> inspectDirectory(Path mods) {
        List<ModInfo> result = new ArrayList<>();
        if (!Files.isDirectory(mods)) return result;
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path jar : jars) {
                try { result.add(inspect(jar)); }
                catch (IOException failure) { result.add(new ModInfo(jar, "unknown", "unknown", false, failure.getMessage())); }
            }
        } catch (IOException ignored) { }
        return result;
    }

    public ModInfo inspect(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.size() > MAX_ENTRIES) throw new IOException("too many ZIP entries");
            ZipEntry fabric = zip.getEntry("fabric.mod.json");
            if (fabric != null) {
                JsonNode node = readJson(zip, fabric);
                return new ModInfo(jar, node.path("id").asText("unknown"), node.path("version").asText("unknown"),
                        isCompanion(node.path("id").asText()), "fabric");
            }
            ZipEntry neo = zip.getEntry("META-INF/neoforge.mods.toml");
            if (neo != null) return tomlInfo(jar, zip, neo, "neoforge");
            ZipEntry forge = zip.getEntry("META-INF/mods.toml");
            if (forge != null) return tomlInfo(jar, zip, forge, "forge");
            return new ModInfo(jar, "unknown", "unknown", false, "no supported metadata");
        }
    }
    private static JsonNode readJson(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > MAX_ENTRY) throw new IOException("metadata entry too large");
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes((int) MAX_ENTRY + 1);
            if (bytes.length > MAX_ENTRY) throw new IOException("metadata entry too large");
            return JSON.readTree(bytes);
        }
    }
    private static ModInfo tomlInfo(Path jar, ZipFile zip, ZipEntry entry, String loader) throws IOException {
        if (entry.getSize() > MAX_ENTRY) throw new IOException("metadata entry too large");
        String text;
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes((int) MAX_ENTRY + 1);
            if (bytes.length > MAX_ENTRY) throw new IOException("metadata entry too large");
            text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        String id = match(text, "(?m)^\\s*modId\\s*=\\s*[\"']([^\"']+)");
        String version = match(text, "(?m)^\\s*version\\s*=\\s*[\"']([^\"']+)");
        return new ModInfo(jar, id, version, isCompanion(id), loader);
    }
    private static String match(String text, String pattern) {
        var matcher = java.util.regex.Pattern.compile(pattern).matcher(text);
        return matcher.find() ? matcher.group(1) : "unknown";
    }
    private static boolean isCompanion(String id) {
        String value = id == null ? "" : id.toLowerCase(Locale.ROOT);
        return value.equals("minecraft_ai_companion") || value.equals("minecraft-ai-companion") || value.equals("mccompanion");
    }
    public record ModInfo(Path jar, String id, String version, boolean companion, String metadataType) {}
}
