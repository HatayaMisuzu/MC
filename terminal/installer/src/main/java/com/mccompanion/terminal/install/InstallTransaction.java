package com.mccompanion.terminal.install;

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

public final class InstallTransaction {
    private static final ObjectMapper JSON = new ObjectMapper();
    public Result execute(InstallPlan plan) throws IOException {
        Path gameDir = plan.instance().gameDirectory().toAbsolutePath().normalize();
        Path state = gameDir.resolve(".mccompanion");
        Path backup = state.resolve("backups").resolve(plan.rollbackId());
        Files.createDirectories(state);
        try (FileChannel channel = FileChannel.open(state.resolve("install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.tryLock()) {
            if (ignored == null) throw new IOException("Another install transaction holds the instance lock");
            Files.createDirectories(plan.instance().modsDirectory());
            Files.createDirectories(backup);
            List<Path> moved = new ArrayList<>();
            Path temporary = plan.destination().resolveSibling(plan.destination().getFileName() + ".mcac.tmp");
            try {
                for (Path existing : plan.replacedFiles()) if (Files.isRegularFile(existing)) {
                    Path target = backup.resolve(existing.getFileName());
                    Files.move(existing, target, StandardCopyOption.REPLACE_EXISTING);
                    moved.add(target);
                }
                Files.copy(plan.artifact(), temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!sha256(plan.artifact()).equals(sha256(temporary))) throw new IOException("Artifact hash mismatch after copy");
                atomicMove(temporary, plan.destination());
                writeManifest(plan, state.resolve("install-manifest.json"), sha256(plan.destination()));
                return new Result(plan.destination(), sha256(plan.destination()), plan.rollbackId());
            } catch (IOException failure) {
                Files.deleteIfExists(temporary);
                Files.deleteIfExists(plan.destination());
                for (Path saved : moved) Files.move(saved, plan.instance().modsDirectory().resolve(saved.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                throw failure;
            }
        }
    }

    public void rollback(Path gameDir, String rollbackId) throws IOException {
        Path normalized = gameDir.toAbsolutePath().normalize();
        Path state = normalized.resolve(".mccompanion");
        Path manifest = state.resolve("install-manifest.json");
        if (Files.isRegularFile(manifest)) {
            String installed = JSON.readTree(manifest.toFile()).path("installedFile").asText();
            Path candidate = normalized.resolve(installed).normalize();
            if (candidate.startsWith(normalized.resolve("mods"))) Files.deleteIfExists(candidate);
        }
        Path backup = state.resolve("backups").resolve(rollbackId).normalize();
        if (!backup.startsWith(state.resolve("backups")) || !Files.isDirectory(backup)) throw new IOException("Unknown rollback point");
        try (var files = Files.newDirectoryStream(backup)) {
            Files.createDirectories(normalized.resolve("mods"));
            for (Path saved : files) Files.move(saved, normalized.resolve("mods").resolve(saved.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(manifest);
    }
    public boolean verify(Path gameDir)throws IOException{Path root=gameDir.toAbsolutePath().normalize();Path manifest=root.resolve(".mccompanion/install-manifest.json");if(!Files.isRegularFile(manifest))return false;var node=JSON.readTree(manifest.toFile());int schema=node.path("schemaVersion").asInt(1);if(schema!=1)throw new IOException("Unsupported install manifest schema: "+schema);Path installed=root.resolve(node.path("installedFile").asText()).normalize();return installed.startsWith(root.resolve("mods"))&&Files.isRegularFile(installed)&&sha256(installed).equals(node.path("sha256").asText());}
    public void uninstall(Path gameDir)throws IOException{Path root=gameDir.toAbsolutePath().normalize();Path manifest=root.resolve(".mccompanion/install-manifest.json");if(!Files.isRegularFile(manifest))throw new IOException("No managed install manifest");var node=JSON.readTree(manifest.toFile());Path installed=root.resolve(node.path("installedFile").asText()).normalize();if(!installed.startsWith(root.resolve("mods")))throw new IOException("Unsafe managed file path");Files.deleteIfExists(installed);Files.deleteIfExists(manifest);}
    public List<String> rollbackPoints(Path gameDir)throws IOException{Path root=gameDir.toAbsolutePath().normalize().resolve(".mccompanion/backups");if(!Files.isDirectory(root))return List.of();try(var dirs=Files.newDirectoryStream(root,Files::isDirectory)){List<String> values=new ArrayList<>();for(Path p:dirs)values.add(p.getFileName().toString());return values.stream().sorted().toList();}}

    private static void writeManifest(InstallPlan plan, Path file, String hash) throws IOException {
        ObjectNode root = JSON.createObjectNode().put("schemaVersion", 1).put("installationId", plan.rollbackId())
                .put("instanceId", plan.instance().instanceId()).put("installedAt", Instant.now().toString())
                .put("installedFile", plan.instance().gameDirectory().relativize(plan.destination()).toString().replace('\\', '/'))
                .put("sha256", hash).put("backupId", plan.rollbackId());
        ArrayNode replaced = root.putArray("replacedFiles");
        plan.replacedFiles().forEach(path -> replaced.add(path.getFileName().toString()));
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), root);
        atomicMove(temp, file);
    }
    private static String sha256(Path file) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void atomicMove(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    public record Result(Path installedFile, String sha256, String rollbackId) {}
}
