package com.mccompanion.terminal.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Crash-recoverable, cross-process serialized installer transaction. */
public final class InstallTransaction {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREVIOUS_MANIFEST_BACKUP = ".previous-install-manifest.json";
    private static final String ACTIVE_ROLLBACK_POINT = ".rollback-point.json";
    private static final String CONSUMED_ROLLBACK_POINT = ".rollback-point.consumed.json";
    private static final Object[] JVM_LOCKS = new Object[64];
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
    private static final int WINDOWS_ATOMIC_MOVE_ATTEMPTS = 6;
    static {
        java.util.Arrays.setAll(JVM_LOCKS, ignored -> new Object());
    }
    private final FaultInjector faultInjector;

    public InstallTransaction() {
        this(phase -> { });
    }

    InstallTransaction(FaultInjector faultInjector) {
        this.faultInjector = java.util.Objects.requireNonNull(faultInjector);
    }

    public Result execute(InstallPlan plan) throws IOException {
        Path game = safeGameDirectory(plan.instance().gameDirectory());
        return locked(game, () -> executeLocked(plan, game));
    }

    /** Completes rollback of an interrupted installer journal without starting a new install. */
    public void recover(Path gameDir) throws IOException {
        Path game = safeGameDirectory(gameDir);
        locked(game, () -> {
            recoverInterrupted(game, state(game));
            return null;
        });
    }

    private Result executeLocked(InstallPlan plan, Path game) throws IOException {
        Path state = state(game);
        recoverInterrupted(game, state);
        Path backup = state.resolve("backups").resolve(plan.rollbackId()).normalize();
        requireInside(backup, state.resolve("backups"), "Unsafe backup path");
        Files.createDirectories(plan.instance().modsDirectory());
        assertNoReparseEscape(game, plan.instance().modsDirectory());
        if (Files.exists(backup, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Rollback point already exists: " + plan.rollbackId());
        }
        Files.createDirectories(backup);
        assertNoReparseEscape(game, backup);
        Path temporary = plan.destination().resolveSibling(plan.destination().getFileName() + ".mcac.tmp");
        Path manifest = state.resolve("install-manifest.json");
        Path previousManifest = backup.resolve(PREVIOUS_MANIFEST_BACKUP);
        List<Path> replacements = existingReplacements(plan);
        List<String> moved = new ArrayList<>();
        boolean destinationExisted = Files.isRegularFile(plan.destination());
        boolean previousManifestExisted = Files.isRegularFile(manifest);
        String artifactHash = sha256(plan.artifact());
        boolean destinationInstalled = false;
        boolean manifestUpdated = false;
        writeJournal(state, plan, backup, replacements, moved, destinationExisted,
                previousManifestExisted, artifactHash, destinationInstalled, manifestUpdated);
        try {
            faultInjector.at(Phase.AFTER_PREPARED);
            if (previousManifestExisted) {
                atomicCopy(manifest, previousManifest);
            }
            writeJournal(state, plan, backup, replacements, moved, destinationExisted,
                    previousManifestExisted, artifactHash, destinationInstalled, manifestUpdated);
            for (Path existing : replacements) {
                Path source = existing.toRealPath();
                requireInside(source, plan.instance().modsDirectory().toRealPath(), "Managed replacement escapes mods");
                Path target = backup.resolve(existing.getFileName()).normalize();
                requireInside(target, backup, "Unsafe backup file");
                atomicMove(source, target);
                faultInjector.at(Phase.AFTER_REPLACEMENT_MOVE_BEFORE_JOURNAL);
                moved.add(existing.getFileName().toString());
                writeJournal(state, plan, backup, replacements, moved, destinationExisted,
                        previousManifestExisted, artifactHash, destinationInstalled, manifestUpdated);
            }
            faultInjector.at(Phase.AFTER_BACKUP);
            Files.copy(plan.artifact(), temporary, StandardCopyOption.REPLACE_EXISTING);
            if (!artifactHash.equals(sha256(temporary))) {
                throw new IOException("Artifact hash mismatch after copy");
            }
            atomicMove(temporary, plan.destination());
            faultInjector.at(Phase.AFTER_DESTINATION_MOVE_BEFORE_JOURNAL);
            destinationInstalled = true;
            writeJournal(state, plan, backup, replacements, moved, destinationExisted,
                    previousManifestExisted, artifactHash, destinationInstalled, manifestUpdated);
            faultInjector.at(Phase.AFTER_INSTALL);
            String hash = sha256(plan.destination());
            writeManifest(plan, manifest, hash);
            manifestUpdated = true;
            writeJournal(state, plan, backup, replacements, moved, destinationExisted,
                    previousManifestExisted, artifactHash, destinationInstalled, manifestUpdated);
            faultInjector.at(Phase.AFTER_MANIFEST);
            writeRollbackPoint(backup, plan.rollbackId(), moved, previousManifestExisted);
            Files.deleteIfExists(state.resolve("transaction.json"));
            return new Result(plan.destination(), hash, plan.rollbackId());
        } catch (IOException failure) {
            try {
                recoverInterrupted(game, state);
            } catch (IOException recoveryFailure) {
                failure.addSuppressed(recoveryFailure);
            }
            throw failure;
        }
    }

    public void rollback(Path gameDir, String rollbackId) throws IOException {
        Path game = safeGameDirectory(gameDir);
        locked(game, () -> {
            Path state = state(game);
            recoverInterrupted(game, state);
            Path backup = state.resolve("backups").resolve(rollbackId).normalize();
            requireInside(backup, state.resolve("backups"), "Unknown rollback point");
            assertNoReparseEscape(game, backup);
            if (!Files.isDirectory(backup)) throw new IOException("Unknown rollback point");
            Path mods = game.resolve("mods");
            Files.createDirectories(mods);
            assertNoReparseEscape(game, mods);
            Path manifest = state.resolve("install-manifest.json");
            List<String> backupFiles = preflightRollback(game, manifest, mods, backup, rollbackId);
            markRollbackPointConsumed(backup);
            deleteManagedArtifact(game, manifest, true);
            restoreRollbackFiles(mods, backup, backupFiles);
            restoreRollbackManifest(manifest, backup);
            deleteTree(backup, state.resolve("backups"));
            return null;
        });
    }

    public boolean verify(Path gameDir) throws IOException {
        Path game = safeGameDirectory(gameDir);
        Path manifest = state(game).resolve("install-manifest.json");
        if (!Files.isRegularFile(manifest)) return false;
        JsonNode node = readManifest(manifest);
        Path installed = managedPath(game, node);
        return Files.isRegularFile(installed) && sha256(installed).equals(node.path("sha256").asText());
    }

    public void uninstall(Path gameDir) throws IOException {
        uninstall(gameDir, UninstallMode.PRESERVE_USER_DATA);
    }

    public void uninstall(Path gameDir, UninstallMode mode) throws IOException {
        Path game = safeGameDirectory(gameDir);
        locked(game, () -> {
            Path state = state(game);
            recoverInterrupted(game, state);
            Path manifest = state.resolve("install-manifest.json");
            if (!Files.isRegularFile(manifest)) throw new IOException("No managed install manifest");
            deleteManagedArtifact(game, manifest, true);
            Files.deleteIfExists(manifest);
            return null;
        });
        if (mode == UninstallMode.DELETE_INSTANCE_USER_DATA) {
            deleteTree(game.resolve("config").resolve("minecraft-ai-companion"), game);
            deleteTree(game.resolve(".mccompanion"), game);
        }
    }

    public List<String> rollbackPoints(Path gameDir) throws IOException {
        Path root = state(safeGameDirectory(gameDir)).resolve("backups");
        if (!Files.isDirectory(root)) return List.of();
        try (var dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            List<String> values = new ArrayList<>();
            for (Path path : dirs) {
                if (isActiveRollbackPoint(path)) values.add(path.getFileName().toString());
            }
            return values.stream().sorted().toList();
        }
    }

    private static void recoverInterrupted(Path game, Path state) throws IOException {
        Path journal = state.resolve("transaction.json");
        if (!Files.isRegularFile(journal)) return;
        JsonNode node = JSON.readTree(journal.toFile());
        Path destination = game.resolve(node.path("destination").asText()).normalize();
        requireInside(destination, game.resolve("mods"), "Interrupted destination is unsafe");
        Path backup = state.resolve(node.path("backup").asText()).normalize();
        requireInside(backup, state.resolve("backups"), "Interrupted backup is unsafe");
        Files.deleteIfExists(destination.resolveSibling(destination.getFileName() + ".mcac.tmp"));
        int schema = node.path("schemaVersion").asInt(1);
        if (schema == 1) {
            recoverLegacyTransaction(game, state, node, destination, backup);
        } else if (schema == 2) {
            recoverCurrentTransaction(game, state, node, destination, backup);
        } else {
            throw new IOException("Unsupported install transaction schema: " + schema);
        }
        deleteTree(backup, state.resolve("backups"));
        Files.deleteIfExists(journal);
    }

    private static void recoverCurrentTransaction(Path game, Path state, JsonNode node,
                                                   Path destination, Path backup) throws IOException {
        boolean destinationExisted = node.path("destinationExisted").asBoolean(false);
        String artifactHash = node.path("artifactSha256").asText("");
        boolean destinationBackupExists = Files.isRegularFile(backup.resolve(destination.getFileName()));
        boolean destinationMatchesArtifact = Files.isRegularFile(destination)
                && !artifactHash.isBlank() && artifactHash.equals(sha256(destination));
        if (destinationMatchesArtifact && (!destinationExisted || destinationBackupExists)) {
            Files.delete(destination);
        } else if (destinationBackupExists && Files.exists(destination)) {
            throw new IOException("Interrupted install destination changed; refusing to overwrite it during recovery");
        }
        Path manifest = state.resolve("install-manifest.json");
        Path previousManifest = backup.resolve(PREVIOUS_MANIFEST_BACKUP);
        if (Files.isRegularFile(previousManifest)) {
            atomicMove(previousManifest, manifest);
        } else if (!node.path("previousManifestExisted").asBoolean(false)) {
            Files.deleteIfExists(manifest);
        }
        restoreBackup(game.resolve("mods"), backup);
    }

    private static void recoverLegacyTransaction(Path game, Path state, JsonNode node,
                                                  Path destination, Path backup) throws IOException {
        boolean destinationWasReplacement = false;
        for (JsonNode replacement : node.path("replacements")) {
            if (destination.getFileName().toString().equals(replacement.asText())) {
                destinationWasReplacement = true;
                break;
            }
        }
        boolean destinationBackupExists = Files.isRegularFile(backup.resolve(destination.getFileName()));
        String phase = node.path("phase").asText("PREPARED");
        if (destinationBackupExists || (!destinationWasReplacement && !"PREPARED".equals(phase))) {
            Files.deleteIfExists(destination);
        }
        Path manifest = state.resolve("install-manifest.json");
        Path previousManifest = state.resolve("transaction-previous-manifest.json");
        if (Files.isRegularFile(previousManifest)) {
            atomicMove(previousManifest, manifest);
        } else if (destinationBackupExists || (!destinationWasReplacement && !"PREPARED".equals(phase))) {
            Files.deleteIfExists(manifest);
        }
        restoreBackup(game.resolve("mods"), backup);
    }

    private static List<Path> existingReplacements(InstallPlan plan) throws IOException {
        List<Path> replacements = new ArrayList<>();
        for (Path candidate : plan.replacedFiles()) {
            if (!Files.isRegularFile(candidate)) continue;
            Path normalized = candidate.toAbsolutePath().normalize();
            if (replacements.stream().noneMatch(normalized::equals)) replacements.add(normalized);
        }
        Path destination = plan.destination().toAbsolutePath().normalize();
        if (Files.isRegularFile(destination) && replacements.stream().noneMatch(destination::equals)) {
            replacements.add(destination);
        }
        return replacements;
    }

    private static void writeJournal(Path state, InstallPlan plan, Path backup,
                                     List<Path> replacements, List<String> moved,
                                     boolean destinationExisted, boolean previousManifestExisted,
                                     String artifactHash, boolean destinationInstalled,
                                     boolean manifestUpdated) throws IOException {
        ObjectNode root = JSON.createObjectNode().put("schemaVersion", 2).put("phase", "ACTIVE")
                .put("destination", plan.instance().gameDirectory().relativize(plan.destination()).toString().replace('\\', '/'))
                .put("backup", state.relativize(backup).toString().replace('\\', '/'))
                .put("destinationExisted", destinationExisted)
                .put("previousManifestExisted", previousManifestExisted)
                .put("previousManifestBackedUp", Files.isRegularFile(backup.resolve(PREVIOUS_MANIFEST_BACKUP)))
                .put("artifactSha256", artifactHash)
                .put("destinationInstalled", destinationInstalled)
                .put("manifestUpdated", manifestUpdated);
        ArrayNode replacementValues = root.putArray("replacements");
        for (Path path : replacements) {
            replacementValues.addObject().put("file", path.getFileName().toString())
                    .put("moved", moved.contains(path.getFileName().toString()));
        }
        atomicJson(root, state.resolve("transaction.json"));
    }

    private static void writeManifest(InstallPlan plan, Path file, String hash) throws IOException {
        ObjectNode root = JSON.createObjectNode().put("schemaVersion", 2).put("installationId", plan.rollbackId())
                .put("instanceId", plan.instance().instanceId()).put("installedAt", Instant.now().toString())
                .put("minecraftVersion", plan.instance().minecraftVersion()).put("loader", plan.instance().loader().name())
                .put("installedFile", plan.instance().gameDirectory().relativize(plan.destination()).toString().replace('\\', '/'))
                .put("sha256", hash).put("backupId", plan.rollbackId());
        ArrayNode replaced = root.putArray("replacedFiles");
        plan.replacedFiles().forEach(path -> replaced.add(path.getFileName().toString()));
        atomicJson(root, file);
    }

    private static void writeRollbackPoint(Path backup, String rollbackId, List<String> backupFiles,
                                           boolean previousManifestExisted) throws IOException {
        ObjectNode root = JSON.createObjectNode().put("schemaVersion", 1)
                .put("rollbackId", rollbackId)
                .put("previousManifestExisted", previousManifestExisted);
        ArrayNode files = root.putArray("backupFiles");
        backupFiles.forEach(files::add);
        atomicJson(root, backup.resolve(ACTIVE_ROLLBACK_POINT));
    }

    private static List<String> preflightRollback(Path game, Path manifest, Path mods, Path backup,
                                                  String rollbackId) throws IOException {
        Path active = backup.resolve(ACTIVE_ROLLBACK_POINT);
        if (!Files.isRegularFile(active) || Files.exists(backup.resolve(CONSUMED_ROLLBACK_POINT))) {
            throw new IOException("Rollback point is unknown or already consumed: " + rollbackId);
        }
        JsonNode point = JSON.readTree(active.toFile());
        if (point.path("schemaVersion").asInt(-1) != 1
                || !rollbackId.equals(point.path("rollbackId").asText())) {
            throw new IOException("Rollback point metadata is invalid: " + rollbackId);
        }
        JsonNode backupFiles = point.path("backupFiles");
        if (!backupFiles.isArray() || !point.path("previousManifestExisted").isBoolean()) {
            throw new IOException("Rollback point metadata is incomplete: " + rollbackId);
        }
        boolean previousManifestExisted = point.path("previousManifestExisted").asBoolean();
        Path previousManifest = backup.resolve(PREVIOUS_MANIFEST_BACKUP);
        if (previousManifestExisted != Files.isRegularFile(previousManifest)) {
            throw new IOException("Rollback point previous manifest backup is incomplete: " + rollbackId);
        }
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("Current managed install manifest is missing");
        }
        JsonNode currentManifest = readManifest(manifest);
        Path currentManaged = managedPath(game, currentManifest);
        if (!Files.isRegularFile(currentManaged)
                || !sha256(currentManaged).equals(currentManifest.path("sha256").asText())) {
            throw new IOException("Current managed artifact is missing or modified");
        }
        List<String> expectedFiles = new ArrayList<>();
        for (JsonNode value : backupFiles) {
            String name = value.asText("");
            if (name.isBlank() || !Path.of(name).getFileName().toString().equals(name)
                    || ACTIVE_ROLLBACK_POINT.equals(name) || CONSUMED_ROLLBACK_POINT.equals(name)
                    || PREVIOUS_MANIFEST_BACKUP.equals(name) || expectedFiles.contains(name)) {
                throw new IOException("Rollback point contains an unsafe backup name");
            }
            Path saved = backup.resolve(name).normalize();
            requireInside(saved, backup, "Rollback point backup escapes its directory");
            assertNoReparseEscape(game, saved);
            if (!Files.isRegularFile(saved)) {
                throw new IOException("Rollback point backup is incomplete: " + name);
            }
            Path destination = mods.resolve(name).normalize();
            requireInside(destination, mods, "Rollback destination escapes mods directory");
            if (Files.exists(destination) && !destination.equals(currentManaged)) {
                throw new IOException("Rollback destination already exists: " + name);
            }
            expectedFiles.add(name);
        }
        try (var files = Files.newDirectoryStream(backup, Files::isRegularFile)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!ACTIVE_ROLLBACK_POINT.equals(name) && !PREVIOUS_MANIFEST_BACKUP.equals(name)
                        && !expectedFiles.contains(name)) {
                    throw new IOException("Rollback point contains unexpected backup content: " + name);
                }
            }
        }
        return List.copyOf(expectedFiles);
    }

    private static boolean isActiveRollbackPoint(Path backup) {
        Path active = backup.resolve(ACTIVE_ROLLBACK_POINT);
        if (!Files.isRegularFile(active) || Files.exists(backup.resolve(CONSUMED_ROLLBACK_POINT))) return false;
        try {
            JsonNode point = JSON.readTree(active.toFile());
            if (point.path("schemaVersion").asInt(-1) != 1
                    || !backup.getFileName().toString().equals(point.path("rollbackId").asText())
                    || !point.path("previousManifestExisted").isBoolean()
                    || !point.path("backupFiles").isArray()) return false;
            if (point.path("previousManifestExisted").asBoolean()
                    && !Files.isRegularFile(backup.resolve(PREVIOUS_MANIFEST_BACKUP))) return false;
            for (JsonNode value : point.path("backupFiles")) {
                String name = value.asText("");
                if (name.isBlank() || !Path.of(name).getFileName().toString().equals(name)
                        || !Files.isRegularFile(backup.resolve(name))) return false;
            }
            return true;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static void markRollbackPointConsumed(Path backup) throws IOException {
        Path active = backup.resolve(ACTIVE_ROLLBACK_POINT);
        Path consumed = backup.resolve(CONSUMED_ROLLBACK_POINT);
        if (!Files.isRegularFile(active) || Files.exists(consumed)) {
            throw new IOException("Rollback point is already consumed");
        }
        atomicMoveNew(active, consumed);
    }

    private static JsonNode readManifest(Path manifest) throws IOException {
        JsonNode node = JSON.readTree(manifest.toFile());
        int schema = node.path("schemaVersion").asInt(1);
        if (schema != 1 && schema != 2) throw new IOException("Unsupported install manifest schema: " + schema);
        return node;
    }

    private static void deleteManagedArtifact(Path game, Path manifest, boolean requireHash) throws IOException {
        if (!Files.isRegularFile(manifest)) return;
        JsonNode node = readManifest(manifest);
        Path installed = managedPath(game, node);
        if (Files.exists(installed) && requireHash && !sha256(installed).equals(node.path("sha256").asText())) {
            throw new IOException("Managed artifact was modified; refusing to delete it during uninstall");
        }
        Files.deleteIfExists(installed);
    }

    private static Path managedPath(Path game, JsonNode manifest) throws IOException {
        Path installed = game.resolve(manifest.path("installedFile").asText()).normalize();
        requireInside(installed, game.resolve("mods"), "Unsafe managed file path");
        assertNoReparseEscape(game, installed.getParent());
        return installed;
    }

    private static void restoreRollbackManifest(Path manifest, Path backup) throws IOException {
        Path previousManifest = backup.resolve(PREVIOUS_MANIFEST_BACKUP);
        if (Files.isRegularFile(previousManifest)) atomicMove(previousManifest, manifest);
        else Files.deleteIfExists(manifest);
    }

    private static void restoreBackup(Path mods, Path backup) throws IOException {
        Files.createDirectories(mods);
        if (!Files.isDirectory(backup)) return;
        try (var files = Files.newDirectoryStream(backup, Files::isRegularFile)) {
            for (Path saved : files) {
                String name = saved.getFileName().toString();
                if (name.startsWith(".mcac-copy-") && name.endsWith(".tmp")) {
                    Files.delete(saved);
                } else if (!PREVIOUS_MANIFEST_BACKUP.equals(name)
                        && !ACTIVE_ROLLBACK_POINT.equals(name)
                        && !CONSUMED_ROLLBACK_POINT.equals(name)) {
                    Path destination = mods.resolve(saved.getFileName());
                    if (Files.exists(destination)) {
                        throw new IOException("Backup destination changed; refusing to overwrite it during recovery");
                    }
                    atomicMove(saved, destination);
                }
            }
        }
    }

    private static void restoreRollbackFiles(Path mods, Path backup, List<String> backupFiles) throws IOException {
        for (String name : backupFiles) {
            atomicMoveNew(backup.resolve(name), mods.resolve(name));
        }
    }

    private static Path safeGameDirectory(Path gameDir) throws IOException {
        Path game = gameDir.toRealPath();
        if (!Files.isDirectory(game)) throw new IOException("Game directory does not exist");
        return game;
    }

    private static Path state(Path game) throws IOException {
        Path state = game.resolve(".mccompanion");
        if (Files.exists(state, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            assertNoReparseEscape(game, state);
        }
        Files.createDirectories(state);
        return state;
    }

    private static void assertNoReparseEscape(Path game, Path path) throws IOException {
        Path existing = path;
        while (existing != null
                && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.toRealPath().startsWith(game.toRealPath())) {
            throw new IOException("Managed path escapes game directory through a link, junction or reparse point");
        }
        Path current = game;
        Path relative = game.relativize(path.toAbsolutePath().normalize());
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current)
                    || Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther()
                    || !current.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            .equals(current.toRealPath()))) {
                throw new IOException("Managed path contains a link, junction or reparse point: " + component);
            }
        }
    }

    private static void requireInside(Path candidate, Path root, String message) throws IOException {
        if (!candidate.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException(message);
        }
    }

    private static void deleteTree(Path target, Path boundary) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        requireInside(normalized, boundary.toAbsolutePath().normalize(), "Unsafe user-data deletion path");
        if (!Files.exists(normalized, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(normalized)) throw new IOException("Refusing to delete linked user-data root");
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static String sha256(Path file) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void atomicJson(JsonNode node, Path destination) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".mcac-json-", ".tmp");
        IOException pending = null;
        try {
            Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(node),
                    StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            atomicMove(temporary, destination);
        } catch (IOException failure) {
            pending = failure;
            throw failure;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                if (pending == null) throw cleanupFailure;
                pending.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void atomicCopy(Path source, Path destination) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".mcac-copy-", ".tmp");
        IOException pending = null;
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            atomicMove(temporary, destination);
        } catch (IOException failure) {
            pending = failure;
            throw failure;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                if (pending == null) throw cleanupFailure;
                pending.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        int attempts = WINDOWS ? WINDOWS_ATOMIC_MOVE_ATTEMPTS : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (FileSystemException failure) {
                if (!WINDOWS || attempt + 1 >= attempts || !Files.exists(from)) throw failure;
                try {
                    Thread.sleep(20L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                    throw failure;
                }
            }
        }
    }

    private static void atomicMoveNew(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(from, to);
        }
    }

    private static <T> T locked(Path game, IoSupplier<T> operation) throws IOException {
        Path state = state(game);
        Object monitor = JVM_LOCKS[Math.floorMod(game.hashCode(), JVM_LOCKS.length)];
        synchronized (monitor) {
            try (FileChannel channel = FileChannel.open(state.resolve("install.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return operation.get();
            }
        }
    }

    @FunctionalInterface private interface IoSupplier<T> { T get() throws IOException; }
    @FunctionalInterface interface FaultInjector { void at(Phase phase) throws IOException; }
    enum Phase {
        AFTER_PREPARED,
        AFTER_REPLACEMENT_MOVE_BEFORE_JOURNAL,
        AFTER_BACKUP,
        AFTER_DESTINATION_MOVE_BEFORE_JOURNAL,
        AFTER_INSTALL,
        AFTER_MANIFEST
    }
    public enum UninstallMode { PRESERVE_USER_DATA, DELETE_INSTANCE_USER_DATA }
    public record Result(Path installedFile, String sha256, String rollbackId) { }
}
