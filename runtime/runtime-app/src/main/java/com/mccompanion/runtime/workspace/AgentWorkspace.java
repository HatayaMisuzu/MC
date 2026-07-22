package com.mccompanion.runtime.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.security.Digests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Profile/companion-scoped logical workspace. Runtime callers never receive physical host paths.
 */
public final class AgentWorkspace {
    public static final Quota DEFAULT_QUOTA = new Quota(128, 2 * 1024 * 1024, 64 * 1024);
    static final int BACKUP_RETENTION = 8;
    private static final Set<String> EXTENSIONS = Set.of(".yaml", ".yml", ".json", ".md");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern DRIVE = Pattern.compile("(?i)^[a-z]:.*");
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7",
            "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private final Path root;
    private final String profileScope;
    private final Quota quota;
    private final Clock clock;

    public AgentWorkspace(Path root, String profileId) {
        this(root, profileId, DEFAULT_QUOTA, Clock.systemUTC());
    }

    AgentWorkspace(Path root, String profileId, Quota quota, Clock clock) {
        if (root == null) throw new IllegalArgumentException("workspace root is required");
        this.root = root.toAbsolutePath().normalize();
        this.profileScope = scope(required(profileId, "profileId"));
        this.quota = java.util.Objects.requireNonNull(quota, "quota");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public synchronized WorkspaceResource save(String companionId, String logicalPath, String content)
            throws IOException {
        String path = validateLogicalPath(logicalPath);
        byte[] bytes = requiredContent(content);
        Scope scope = scopeFor(companionId);
        ensureScope(scope);
        ObjectNode index = loadIndex(scope);
        JsonNode previous = index.path(path);
        long oldSize = previous.path("sizeBytes").asLong(0);
        int files = index.size();
        long total = 0;
        for (JsonNode value : index) total = Math.addExact(total, value.path("sizeBytes").asLong());
        if (previous.isMissingNode() && files >= quota.maxFiles()) {
            throw new IllegalArgumentException("workspace file quota exceeded");
        }
        if (total - oldSize + bytes.length > quota.maxTotalBytes()) {
            throw new IllegalArgumentException("workspace byte quota exceeded");
        }

        String hash = Digests.sha256(content);
        if (!previous.isMissingNode() && hash.equals(previous.path("sha256").asText())) {
            return metadata(path, previous);
        }
        Path target = resolveResource(scope, path);
        ensureSecureDirectories(scope.resources(), target.getParent());
        rejectLink(target, scope.root());
        byte[] oldBytes = Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(target) : null;
        long oldVersion = previous.path("version").asLong(0);
        if (oldBytes != null) backup(scope, path, oldVersion, oldBytes);

        long version = Math.addExact(oldVersion, 1);
        long updatedAt = clock.millis();
        ObjectNode value = Json.object().put("version", version).put("sha256", hash)
                .put("sizeBytes", bytes.length).put("updatedAt", updatedAt);
        Path temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            moveAtomically(temporary, target);
            index.set(path, value);
            saveIndex(scope, index);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            if (oldBytes == null) Files.deleteIfExists(target);
            else Files.write(target, oldBytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            throw failure;
        }
        return metadata(path, value);
    }

    public synchronized WorkspaceDocument read(String companionId, String logicalPath) throws IOException {
        String path = validateLogicalPath(logicalPath);
        Scope scope = scopeFor(companionId);
        ensureScope(scope);
        JsonNode value = loadIndex(scope).path(path);
        if (value.isMissingNode()) throw new IllegalArgumentException("workspace resource does not exist");
        Path target = resolveResource(scope, path);
        rejectLink(target, scope.root());
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("workspace resource metadata has no content");
        }
        byte[] bytes = Files.readAllBytes(target);
        if (bytes.length > quota.maxFileBytes()) throw new IOException("workspace resource exceeds file quota");
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (!Digests.sha256(content).equals(value.path("sha256").asText())) {
            throw new IOException("workspace resource integrity check failed");
        }
        return new WorkspaceDocument(metadata(path, value), content);
    }

    /**
     * Restores retained content as a new monotonic version. Historical version numbers are never
     * made current again, so audit consumers cannot confuse a rollback with the original write.
     */
    public synchronized WorkspaceResource restore(String companionId, String logicalPath, long version)
            throws IOException {
        String path = validateLogicalPath(logicalPath);
        if (version < 1) throw new IllegalArgumentException("workspace version is invalid");
        Scope scope = scopeFor(companionId);
        ensureScope(scope);
        JsonNode current = loadIndex(scope).path(path);
        if (current.isMissingNode()) throw new IllegalArgumentException("workspace resource does not exist");
        long currentVersion = current.path("version").asLong();
        if (version > currentVersion) throw new IllegalArgumentException("workspace version does not exist");
        if (version == currentVersion) return read(companionId, path).resource();

        Path directory = backupDirectory(scope, path);
        rejectLink(directory, scope.root());
        Path backup = directory.resolve(version + ".bak");
        Path checksum = directory.resolve(version + ".sha256");
        rejectLink(backup, scope.root());
        rejectLink(checksum, scope.root());
        if (Files.notExists(backup, LinkOption.NOFOLLOW_LINKS)
                || Files.notExists(checksum, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("workspace version is no longer retained");
        }
        byte[] bytes = Files.readAllBytes(backup);
        if (bytes.length == 0 || bytes.length > quota.maxFileBytes()) {
            throw new IOException("workspace backup exceeds file quota");
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        String expectedHash = Files.readString(checksum, StandardCharsets.US_ASCII).strip();
        if (!Digests.sha256(content).equals(expectedHash)) {
            throw new IOException("workspace backup integrity check failed");
        }
        return save(companionId, path, content);
    }

    public synchronized List<WorkspaceResource> list(String companionId, String prefix) throws IOException {
        String boundedPrefix = prefix == null || prefix.isBlank() ? "" : validatePrefix(prefix);
        Scope scope = scopeFor(companionId);
        ensureScope(scope);
        ObjectNode index = loadIndex(scope);
        List<WorkspaceResource> resources = new ArrayList<>();
        index.fields().forEachRemaining(entry -> {
            if (entry.getKey().startsWith(boundedPrefix)) {
                resources.add(metadata(entry.getKey(), entry.getValue()));
            }
        });
        resources.sort(Comparator.comparing(WorkspaceResource::logicalPath));
        return List.copyOf(resources);
    }

    public synchronized List<WorkspaceRetainedVersion> retainedVersions(
            String companionId, String logicalPath) throws IOException {
        String path = validateLogicalPath(logicalPath);
        Scope scope = scopeFor(companionId);
        ensureScope(scope);
        if (loadIndex(scope).path(path).isMissingNode()) {
            throw new IllegalArgumentException("workspace resource does not exist");
        }
        Path directory = backupDirectory(scope, path);
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        rejectLink(directory, scope.root());
        List<WorkspaceRetainedVersion> retained = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path backup : files.filter(value -> value.getFileName().toString()
                    .matches("[1-9][0-9]*\\.bak")).toList()) {
                rejectLink(backup, scope.root());
                long version = backupVersion(backup);
                Path checksum = directory.resolve(version + ".sha256");
                rejectLink(checksum, scope.root());
                if (Files.notExists(checksum, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("workspace backup checksum is missing");
                }
                byte[] bytes = Files.readAllBytes(backup);
                if (bytes.length == 0 || bytes.length > quota.maxFileBytes()) {
                    throw new IOException("workspace backup exceeds file quota");
                }
                String content = new String(bytes, StandardCharsets.UTF_8);
                String hash = Files.readString(checksum, StandardCharsets.US_ASCII).strip();
                if (!Digests.sha256(content).equals(hash)) {
                    throw new IOException("workspace backup integrity check failed");
                }
                retained.add(new WorkspaceRetainedVersion(version, hash, bytes.length));
            }
        }
        retained.sort(Comparator.comparingLong(WorkspaceRetainedVersion::version).reversed());
        return List.copyOf(retained);
    }

    private Scope scopeFor(String companionId) {
        Path profile = root.resolve(profileScope);
        Path scopeRoot = profile.resolve(scope(required(companionId, "companionId")));
        return new Scope(scopeRoot, scopeRoot.resolve("resources"), scopeRoot.resolve(".mcac-index.json"),
                scopeRoot.resolve(".mcac-backups"));
    }

    private void ensureScope(Scope scope) throws IOException {
        ensureSecureDirectories(root, scope.resources());
        ensureSecureDirectories(root, scope.backups());
        rejectLink(scope.index(), scope.root());
    }

    private static void ensureSecureDirectories(Path boundary, Path directory) throws IOException {
        Path absoluteBoundary = boundary.toAbsolutePath().normalize();
        Path absoluteDirectory = directory.toAbsolutePath().normalize();
        if (!absoluteDirectory.startsWith(absoluteBoundary)) throw new IOException("workspace boundary violation");
        Path current = absoluteBoundary;
        if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(current);
        rejectLink(current, absoluteBoundary);
        Path relative = absoluteBoundary.relativize(absoluteDirectory);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current);
            rejectLink(current, absoluteBoundary);
        }
    }

    private static void rejectLink(Path path, Path boundary) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(path) || attributes.isOther()) {
            throw new IOException("workspace links and reparse points are not allowed");
        }
        Path realBoundary = Files.exists(boundary, LinkOption.NOFOLLOW_LINKS)
                ? boundary.toRealPath() : boundary.toAbsolutePath().normalize();
        Path real = path.toRealPath();
        if (!real.startsWith(realBoundary)) throw new IOException("workspace path escaped its scope");
    }

    private Path resolveResource(Scope scope, String logicalPath) {
        Path target = scope.resources().resolve(logicalPath.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(scope.resources())) throw new IllegalArgumentException("workspace path escaped its scope");
        return target;
    }

    private ObjectNode loadIndex(Scope scope) throws IOException {
        if (Files.notExists(scope.index(), LinkOption.NOFOLLOW_LINKS)) return Json.object();
        rejectLink(scope.index(), scope.root());
        if (Files.size(scope.index()) > 256 * 1024) throw new IOException("workspace index exceeds limit");
        JsonNode parsed = Json.parse(Files.readString(scope.index(), StandardCharsets.UTF_8));
        if (!parsed.isObject()) throw new IOException("workspace index is invalid");
        return (ObjectNode) parsed;
    }

    private void saveIndex(Scope scope, ObjectNode index) throws IOException {
        Path temporary = scope.index().resolveSibling(".mcac-index." + UUID.randomUUID() + ".tmp");
        Files.writeString(temporary, Json.canonical(index), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            moveAtomically(temporary, scope.index());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void backup(Scope scope, String logicalPath, long version, byte[] content) throws IOException {
        Path directory = backupDirectory(scope, logicalPath);
        ensureSecureDirectories(scope.root(), directory);
        Path backup = directory.resolve(version + ".bak");
        Path checksum = directory.resolve(version + ".sha256");
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            rejectLink(backup, scope.root());
            if (!java.util.Arrays.equals(Files.readAllBytes(backup), content)) {
                throw new IOException("workspace backup version conflicts with retained content");
            }
        } else {
            Files.write(backup, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        String hash = Digests.sha256(new String(content, StandardCharsets.UTF_8));
        if (Files.exists(checksum, LinkOption.NOFOLLOW_LINKS)) {
            rejectLink(checksum, scope.root());
            if (!Files.readString(checksum, StandardCharsets.US_ASCII).strip().equals(hash)) {
                throw new IOException("workspace backup checksum conflicts with retained content");
            }
        } else {
            Files.writeString(checksum, hash, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        pruneBackups(scope, directory);
    }

    private static Path backupDirectory(Scope scope, String logicalPath) {
        Path directory = scope.backups().resolve(Digests.sha256(logicalPath)).normalize();
        if (!directory.startsWith(scope.backups())) {
            throw new IllegalArgumentException("workspace backup path escaped its scope");
        }
        return directory;
    }

    private static void pruneBackups(Scope scope, Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            List<Path> backups = files
                    .filter(path -> path.getFileName().toString().matches("[1-9][0-9]*\\.bak"))
                    .sorted(Comparator.comparingLong(AgentWorkspace::backupVersion))
                    .toList();
            for (int index = 0; index < backups.size() - BACKUP_RETENTION; index++) {
                Path backup = backups.get(index);
                rejectLink(backup, scope.root());
                Files.delete(backup);
                Path checksum = backup.resolveSibling(backupVersion(backup) + ".sha256");
                rejectLink(checksum, scope.root());
                Files.deleteIfExists(checksum);
            }
        }
    }

    private static long backupVersion(Path path) {
        String name = path.getFileName().toString();
        try {
            return Long.parseLong(name.substring(0, name.length() - 4));
        } catch (NumberFormatException invalid) {
            return Long.MAX_VALUE;
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private byte[] requiredContent(String content) {
        if (content == null) throw new IllegalArgumentException("workspace content is required");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > quota.maxFileBytes()) {
            throw new IllegalArgumentException("workspace content exceeds file quota");
        }
        return bytes;
    }

    private static String validateLogicalPath(String value) {
        String path = required(value, "logicalPath");
        if (!Normalizer.isNormalized(path, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException("workspace path must use Unicode NFC");
        }
        if (path.startsWith("/") || path.startsWith("\\") || DRIVE.matcher(path).matches()
                || path.contains("\\") || path.contains(":") || path.contains("\u0000")) {
            throw new IllegalArgumentException("workspace path is not logical");
        }
        String[] segments = path.split("/", -1);
        if (segments.length < 2 || segments.length > 8) {
            throw new IllegalArgumentException("workspace path depth is invalid");
        }
        for (String segment : segments) validateSegment(segment);
        String lower = path.toLowerCase(Locale.ROOT);
        if (EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            throw new IllegalArgumentException("workspace extension is not allowed");
        }
        if (path.length() > 256) throw new IllegalArgumentException("workspace path is too long");
        return path;
    }

    private static String validatePrefix(String value) {
        String prefix = required(value, "prefix");
        if (!Normalizer.isNormalized(prefix, Normalizer.Form.NFC) || prefix.startsWith("/")
                || prefix.contains("\\") || prefix.contains(":") || prefix.contains("..")
                || prefix.length() > 192) {
            throw new IllegalArgumentException("workspace prefix is invalid");
        }
        for (String segment : prefix.split("/", -1)) {
            if (!segment.isEmpty()) validateSegment(segment);
        }
        return prefix;
    }

    private static void validateSegment(String segment) {
        if (segment.equals(".") || segment.equals("..") || !SEGMENT.matcher(segment).matches()
                || segment.endsWith(".") || segment.endsWith(" ")
                || segment.chars().anyMatch(character -> Character.isISOControl(character)
                || Character.getType(character) == Character.FORMAT)) {
            throw new IllegalArgumentException("workspace path segment is invalid");
        }
        String base = segment.contains(".") ? segment.substring(0, segment.indexOf('.')) : segment;
        if (WINDOWS_RESERVED.contains(base.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("workspace path uses a reserved name");
        }
    }

    private static String scope(String value) {
        return Digests.sha256(value).substring(0, 32);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value.strip();
    }

    private static WorkspaceResource metadata(String path, JsonNode value) {
        return new WorkspaceResource(path, value.path("version").asLong(), value.path("sha256").asText(),
                value.path("sizeBytes").asLong(), Instant.ofEpochMilli(value.path("updatedAt").asLong()));
    }

    public record Quota(int maxFiles, long maxTotalBytes, int maxFileBytes) {
        public Quota {
            if (maxFiles < 1 || maxFiles > 10_000 || maxTotalBytes < 1 || maxFileBytes < 1
                    || maxFileBytes > maxTotalBytes) {
                throw new IllegalArgumentException("workspace quota is invalid");
            }
        }
    }

    private record Scope(Path root, Path resources, Path index, Path backups) {
    }
}
