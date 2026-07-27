package com.mccompanion.compat;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads a declaration-only .mcac-compat archive without extracting it. */
public final class CompatibilityPackLoader {
    public static final long MAX_ARCHIVE_BYTES = 8L * 1024 * 1024;
    public static final int MAX_ENTRIES = 256;
    public static final int MAX_ENTRY_BYTES = 1024 * 1024;
    private static final Set<String> ALLOWED_ROOTS = Set.of(
            "manifest.yaml", "manifest.yml", "fingerprints", "capabilities", "mappings",
            "overlays", "native", "tests", "evidence", "migrations", "LICENSES",
            "SHA256SUMS.txt");
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(".json", ".yaml", ".yml");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectMapper YAML = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public CompatibilityPack load(Path archive) throws IOException {
        Path source = archive.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(source)) {
            throw failure("PACK_NOT_REGULAR");
        }
        long size = Files.size(source);
        if (size < 1 || size > MAX_ARCHIVE_BYTES) throw failure("PACK_SIZE_LIMIT");
        String archiveHash = sha256(source);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            var values = zip.entries();
            while (values.hasMoreElements()) {
                ZipEntry entry = values.nextElement();
                if (entry.isDirectory()) continue;
                String name = safeEntryName(entry.getName());
                if (entries.size() >= MAX_ENTRIES || entries.putIfAbsent(name,
                        readBounded(zip.getInputStream(entry), MAX_ENTRY_BYTES)) != null) {
                    throw failure("PACK_ENTRY_LIMIT_OR_DUPLICATE");
                }
            }
        } catch (IllegalArgumentException invalidZip) {
            throw failure("PACK_ZIP_INVALID", invalidZip);
        }
        byte[] manifestBytes = entries.getOrDefault("manifest.yaml", entries.get("manifest.yml"));
        if (manifestBytes == null) throw failure("MANIFEST_MISSING");
        byte[] sumsBytes = entries.get("SHA256SUMS.txt");
        if (sumsBytes == null) throw failure("HASH_MANIFEST_MISSING");
        Map<String, String> declaredHashes = parseHashes(sumsBytes);
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().equals("SHA256SUMS.txt")) continue;
            String expected = declaredHashes.remove(entry.getKey());
            if (expected == null || !expected.equals(sha256(entry.getValue()))) {
                throw failure("HASH_MISMATCH:" + entry.getKey());
            }
        }
        if (!declaredHashes.isEmpty()) throw failure("HASH_MANIFEST_UNKNOWN_ENTRY");

        Map<String, JsonNode> documents = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (isDocument(entry.getKey())) {
                ObjectMapper mapper = entry.getKey().endsWith(".json") ? JSON : YAML;
                JsonNode value;
                try {
                    value = mapper.readTree(entry.getValue());
                } catch (Exception malformed) {
                    throw failure("DOCUMENT_INVALID:" + entry.getKey(), malformed);
                }
                if (value == null || (!value.isObject() && !value.isArray())) {
                    throw failure("DOCUMENT_ROOT_INVALID:" + entry.getKey());
                }
                documents.put(entry.getKey(), value);
            }
        }
        JsonNode manifestNode = documents.get(entries.containsKey("manifest.yaml")
                ? "manifest.yaml" : "manifest.yml");
        CompatibilityPack.Manifest manifest = manifest(manifestNode);
        boolean nativeEntries = entries.keySet().stream().anyMatch(name -> name.startsWith("native/"));
        if (nativeEntries != manifest.runtime().nativeCode()) {
            throw failure("NATIVE_DECLARATION_MISMATCH");
        }
        return new CompatibilityPack(manifest, archiveHash, documents, hashes(entries));
    }

    private static CompatibilityPack.Manifest manifest(JsonNode root) throws IOException {
        try {
            rejectUnknown(root, Set.of("schemaVersion", "pack", "target", "runtime", "permissions",
                    "dependencies", "conflicts", "replaces", "extends", "patches", "precedence",
                    "limitations"), "manifest");
            JsonNode pack = requiredObject(root, "pack");
            rejectUnknown(pack, Set.of("id", "version", "type", "displayName", "authorType", "createdAt"), "pack");
            JsonNode target = requiredObject(root, "target");
            JsonNode runtime = requiredObject(root, "runtime");
            JsonNode permissions = requiredObject(root, "permissions");
            rejectUnknown(runtime, Set.of("minimumHostVersion", "nativeCode", "hotReloadable",
                    "restartRequired", "nativeProtocol", "nativeArtifacts"), "runtime");
            rejectUnknown(permissions, Set.of("declared", "forbidden"), "permissions");
            return new CompatibilityPack.Manifest(
                    text(root, "schemaVersion"),
                    text(pack, "id"),
                    text(pack, "version"),
                    CompatibilityPack.PackType.parse(text(pack, "type")),
                    text(pack, "displayName"),
                    text(pack, "authorType"),
                    Instant.parse(text(pack, "createdAt")),
                    target(target),
                    new CompatibilityPack.RuntimeDeclaration(
                            runtime.path("minimumHostVersion").asInt(-1),
                            runtime.path("nativeCode").asBoolean(false),
                            runtime.path("hotReloadable").asBoolean(false),
                            runtime.path("restartRequired").asBoolean(false),
                            runtime.path("nativeProtocol").asText(""),
                            strings(runtime.path("nativeArtifacts"), 32)),
                    new CompatibilityPack.Permissions(
                            Set.copyOf(strings(permissions.path("declared"), 64)),
                            Set.copyOf(strings(permissions.path("forbidden"), 64))),
                    strings(root.path("dependencies"), 64),
                    strings(root.path("conflicts"), 64),
                    strings(root.path("replaces"), 64),
                    strings(root.path("extends"), 64),
                    strings(root.path("patches"), 64),
                    root.path("precedence").asInt(0),
                    strings(root.path("limitations"), 64));
        } catch (RuntimeException invalid) {
            throw failure("MANIFEST_INVALID:" + invalid.getMessage(), invalid);
        }
    }

    private static CompatibilityPack.Target target(JsonNode target) {
        rejectUnknown(target, Set.of("minecraft", "loader", "java", "instanceId", "mods",
                "configurationHash", "scriptsHash", "dataPacksHash", "modpackHash"), "target");
        JsonNode minecraft = target.path("minecraft");
        JsonNode loader = target.path("loader");
        JsonNode java = target.path("java");
        if (!minecraft.isMissingNode()) rejectUnknown(minecraft, Set.of("exact", "range"), "target.minecraft");
        if (!loader.isMissingNode()) rejectUnknown(loader, Set.of("type", "versionRange"), "target.loader");
        if (!java.isMissingNode()) rejectUnknown(java, Set.of("minimum", "maximum"), "target.java");
        Map<String, CompatibilityPack.ModTarget> mods = new LinkedHashMap<>();
        JsonNode modNode = target.path("mods");
        if (modNode.isArray()) {
            for (JsonNode mod : modNode) {
                rejectUnknown(mod, Set.of("id", "versionRange", "jarHash"), "target.mods[]");
                String id = CompatibilityPack.identifier(text(mod, "id"), "mod.id");
                if (mods.putIfAbsent(id, new CompatibilityPack.ModTarget(
                        mod.path("versionRange").asText(""), mod.path("jarHash").asText(""))) != null) {
                    throw new IllegalArgumentException("DUPLICATE_MOD_TARGET");
                }
            }
        } else if (modNode.isObject()) {
            modNode.fields().forEachRemaining(entry -> {
                rejectUnknown(entry.getValue(), Set.of("versionRange", "jarHash"),
                        "target.mods." + entry.getKey());
                mods.put(CompatibilityPack.identifier(entry.getKey(), "mod.id"),
                        new CompatibilityPack.ModTarget(entry.getValue().path("versionRange").asText(""),
                                entry.getValue().path("jarHash").asText("")));
            });
        } else if (!modNode.isMissingNode() && !modNode.isNull()) {
            throw new IllegalArgumentException("INVALID_MOD_TARGETS");
        }
        return new CompatibilityPack.Target(
                minecraft.path("exact").asText(""), minecraft.path("range").asText(""),
                loader.path("type").asText(""), loader.path("versionRange").asText(""),
                java.path("minimum").isInt() ? java.path("minimum").asInt() : null,
                java.path("maximum").isInt() ? java.path("maximum").asInt() : null,
                target.path("instanceId").asText(""), mods,
                target.path("configurationHash").asText(""),
                target.path("scriptsHash").asText(""),
                target.path("dataPacksHash").asText(""),
                target.path("modpackHash").asText(""));
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String location) {
        if (!node.isObject()) throw new IllegalArgumentException(location + " must be an object");
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw new IllegalArgumentException(
                    "UNKNOWN_FIELD:" + location + '.' + name);
        });
    }

    private static JsonNode requiredObject(JsonNode root, String name) {
        JsonNode value = root.path(name);
        if (!value.isObject()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root.path(name);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.asText();
    }

    private static List<String> strings(JsonNode node, int maximum) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > maximum) throw new IllegalArgumentException("INVALID_STRING_ARRAY");
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual() || value.asText().length() > 512) {
                throw new IllegalArgumentException("INVALID_STRING_ARRAY_VALUE");
            }
            values.add(value.asText());
        });
        return List.copyOf(values);
    }

    private static String safeEntryName(String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.length() > 256 || raw.startsWith("/")
                || raw.startsWith("\\") || raw.contains("\\") || raw.contains("\0")
                || raw.contains(":") || raw.matches("^[A-Za-z]:.*")) {
            throw failure("UNSAFE_ARCHIVE_PATH");
        }
        Path normalized;
        try {
            normalized = Path.of(raw).normalize();
        } catch (RuntimeException invalid) {
            throw failure("UNSAFE_ARCHIVE_PATH", invalid);
        }
        String name = normalized.toString().replace('\\', '/');
        if (name.equals(".") || name.startsWith("../") || name.contains("/../")
                || !name.equals(raw)) throw failure("UNSAFE_ARCHIVE_PATH");
        String root = name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
        if (!ALLOWED_ROOTS.contains(root)) throw failure("ARCHIVE_ROOT_NOT_ALLOWED:" + root);
        return name;
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maximum) throw failure("PACK_ENTRY_SIZE_LIMIT");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static Map<String, String> parseHashes(byte[] bytes) throws IOException {
        if (bytes.length > 64 * 1024) throw failure("HASH_MANIFEST_SIZE_LIMIT");
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) continue;
            int split = line.indexOf("  ");
            if (split != 64) throw failure("HASH_MANIFEST_INVALID");
            String hash = line.substring(0, split).toLowerCase(Locale.ROOT);
            String path = safeEntryName(line.substring(split + 2));
            if (!hash.matches("[a-f0-9]{64}") || hashes.putIfAbsent(path, hash) != null
                    || path.equals("SHA256SUMS.txt")) throw failure("HASH_MANIFEST_INVALID");
        }
        return hashes;
    }

    private static Map<String, String> hashes(Map<String, byte[]> entries) {
        Map<String, String> hashes = new LinkedHashMap<>();
        entries.forEach((name, value) -> hashes.put(name, sha256(value)));
        return hashes;
    }

    private static boolean isDocument(String name) {
        return DOCUMENT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
    }

    static String sha256(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IOException failure(String code) {
        return new IOException(code);
    }

    private static IOException failure(String code, Throwable cause) {
        return new IOException(code, cause);
    }
}
