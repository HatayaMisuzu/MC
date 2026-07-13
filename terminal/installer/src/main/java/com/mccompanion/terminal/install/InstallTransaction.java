package com.mccompanion.terminal.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
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
import java.util.concurrent.ConcurrentHashMap;

/** Crash-recoverable, cross-process serialized installer transaction. */
public final class InstallTransaction {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ConcurrentHashMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    public Result execute(InstallPlan plan) throws IOException {
        Path game = safeGameDirectory(plan.instance().gameDirectory());
        return locked(game, () -> executeLocked(plan, game));
    }

    private Result executeLocked(InstallPlan plan, Path game) throws IOException {
        Path state = state(game);
        recoverInterrupted(game, state);
        Path backup = state.resolve("backups").resolve(plan.rollbackId()).normalize();
        requireInside(backup, state.resolve("backups"), "Unsafe backup path");
        Files.createDirectories(plan.instance().modsDirectory());
        assertNoReparseEscape(game, plan.instance().modsDirectory());
        Files.createDirectories(backup);
        Path temporary = plan.destination().resolveSibling(plan.destination().getFileName() + ".mcac.tmp");
        List<Path> moved = new ArrayList<>();
        writeJournal(state, plan, backup, "PREPARED");
        try {
            for (Path existing : plan.replacedFiles()) {
                if (!Files.isRegularFile(existing)) continue;
                Path source = existing.toRealPath();
                requireInside(source, plan.instance().modsDirectory().toRealPath(), "Managed replacement escapes mods");
                Path target = backup.resolve(existing.getFileName()).normalize();
                requireInside(target, backup, "Unsafe backup file");
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                moved.add(target);
            }
            writeJournal(state, plan, backup, "BACKED_UP");
            Files.copy(plan.artifact(), temporary, StandardCopyOption.REPLACE_EXISTING);
            if (!sha256(plan.artifact()).equals(sha256(temporary))) {
                throw new IOException("Artifact hash mismatch after copy");
            }
            atomicMove(temporary, plan.destination());
            writeJournal(state, plan, backup, "INSTALLED");
            String hash = sha256(plan.destination());
            writeManifest(plan, state.resolve("install-manifest.json"), hash);
            Files.deleteIfExists(state.resolve("transaction.json"));
            return new Result(plan.destination(), hash, plan.rollbackId());
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(plan.destination());
            restoreBackup(plan.instance().modsDirectory(), backup, moved);
            Files.deleteIfExists(state.resolve("transaction.json"));
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
            if (!Files.isDirectory(backup)) throw new IOException("Unknown rollback point");
            deleteManagedArtifact(game, state.resolve("install-manifest.json"), false);
            Path mods = game.resolve("mods");
            Files.createDirectories(mods);
            assertNoReparseEscape(game, mods);
            try (var files = Files.newDirectoryStream(backup, Files::isRegularFile)) {
                for (Path saved : files) atomicMove(saved, mods.resolve(saved.getFileName()));
            }
            Files.deleteIfExists(state.resolve("install-manifest.json"));
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
    }

    public List<String> rollbackPoints(Path gameDir) throws IOException {
        Path root = state(safeGameDirectory(gameDir)).resolve("backups");
        if (!Files.isDirectory(root)) return List.of();
        try (var dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            List<String> values = new ArrayList<>();
            for (Path path : dirs) values.add(path.getFileName().toString());
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
        if (!Files.isRegularFile(state.resolve("install-manifest.json"))) {
            Files.deleteIfExists(destination);
            if (Files.isDirectory(backup)) restoreBackup(game.resolve("mods"), backup, null);
        }
        Files.deleteIfExists(journal);
    }

    private static void writeJournal(Path state, InstallPlan plan, Path backup, String phase) throws IOException {
        ObjectNode root = JSON.createObjectNode().put("schemaVersion", 1).put("phase", phase)
                .put("destination", plan.instance().gameDirectory().relativize(plan.destination()).toString().replace('\\', '/'))
                .put("backup", state.relativize(backup).toString().replace('\\', '/'));
        ArrayNode replacements = root.putArray("replacements");
        plan.replacedFiles().forEach(path -> replacements.add(path.getFileName().toString()));
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

    private static void restoreBackup(Path mods, Path backup, List<Path> selected) throws IOException {
        Files.createDirectories(mods);
        if (!Files.isDirectory(backup)) return;
        if (selected != null) {
            for (Path saved : selected) if (Files.exists(saved)) atomicMove(saved, mods.resolve(saved.getFileName()));
            return;
        }
        try (var files = Files.newDirectoryStream(backup, Files::isRegularFile)) {
            for (Path saved : files) atomicMove(saved, mods.resolve(saved.getFileName()));
        }
    }

    private static Path safeGameDirectory(Path gameDir) throws IOException {
        Path game = gameDir.toRealPath();
        if (!Files.isDirectory(game)) throw new IOException("Game directory does not exist");
        return game;
    }

    private static Path state(Path game) throws IOException {
        Path state = game.resolve(".mccompanion");
        if (Files.exists(state)) assertNoReparseEscape(game, state);
        Files.createDirectories(state);
        return state;
    }

    private static void assertNoReparseEscape(Path game, Path path) throws IOException {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null || !existing.toRealPath().startsWith(game.toRealPath())) {
            throw new IOException("Managed path escapes game directory through a link, junction or reparse point");
        }
        Path current = game;
        Path relative = game.relativize(path.toAbsolutePath().normalize());
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.exists(current) && (Files.isSymbolicLink(current)
                    || Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class).isOther())) {
                throw new IOException("Managed path contains a link, junction or reparse point: " + component);
            }
        }
    }

    private static void requireInside(Path candidate, Path root, String message) throws IOException {
        if (!candidate.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException(message);
        }
    }

    private static String sha256(Path file) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void atomicJson(JsonNode node, Path destination) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".mcac-json-", ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), node);
        atomicMove(temporary, destination);
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static <T> T locked(Path game, IoSupplier<T> operation) throws IOException {
        Path state = state(game);
        Object monitor = JVM_LOCKS.computeIfAbsent(game, ignored -> new Object());
        synchronized (monitor) {
            try (FileChannel channel = FileChannel.open(state.resolve("install.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return operation.get();
            }
        }
    }

    @FunctionalInterface private interface IoSupplier<T> { T get() throws IOException; }
    public record Result(Path installedFile, String sha256, String rollbackId) { }
}
