package com.mccompanion.compat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Signed index and immutable content-addressed store with crash recovery. */
public final class CompatibilityStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<CompatibilityPack.PackState> STATE_DIRECTORIES = Set.of(
            CompatibilityPack.PackState.STAGING, CompatibilityPack.PackState.TESTED,
            CompatibilityPack.PackState.VERIFIED, CompatibilityPack.PackState.ACTIVE,
            CompatibilityPack.PackState.DISABLED, CompatibilityPack.PackState.QUARANTINED,
            CompatibilityPack.PackState.SUPERSEDED, CompatibilityPack.PackState.REVOKED);
    private final Path root;
    private final Path objects;
    private final Path indexFile;
    private final Path signatureFile;
    private final Path keyFile;
    private final Path journalFile;
    private final Path lockFile;
    private final Clock clock;
    private final FaultInjector faults;
    private final ReentrantLock localLock = new ReentrantLock(true);
    private final byte[] signingKey;

    public CompatibilityStore(Path root) throws IOException {
        this(root, Clock.systemUTC(), phase -> { });
    }

    CompatibilityStore(Path root, Clock clock, FaultInjector faults) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.faults = java.util.Objects.requireNonNull(faults, "faults");
        if (Files.exists(this.root, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(this.root)) {
            throw new IOException("COMPAT_STORE_SYMLINK_REJECTED");
        }
        Files.createDirectories(this.root);
        Path real = this.root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.equals(this.root.toRealPath())) throw new IOException("COMPAT_STORE_REPARSE_REJECTED");
        objects = this.root.resolve("cache/objects");
        indexFile = this.root.resolve("index/active-index.json");
        signatureFile = this.root.resolve("index/active-index.sig");
        keyFile = this.root.resolve("metadata/store-signing.key");
        journalFile = this.root.resolve("journal/transaction.json");
        lockFile = this.root.resolve("journal/store.lock");
        for (String directory : List.of("staging", "tested", "verified", "active", "disabled",
                "quarantined", "superseded", "revoked", "rollback", "cache", "metadata",
                "index", "journal")) Files.createDirectories(this.root.resolve(directory));
        Files.createDirectories(objects);
        signingKey = loadOrCreateKey();
        locked(() -> {
            recoverLocked();
            if (!Files.exists(indexFile)) writeIndex(StoreIndex.empty());
            readIndex();
            rebuildStateMarkers(readIndex());
            return null;
        });
    }

    public Path root() {
        return root;
    }

    public List<StoredPack> list() throws IOException {
        return locked(() -> readIndex().packs().values().stream()
                .sorted(Comparator.comparing(StoredPack::packId).thenComparing(StoredPack::version))
                .toList());
    }

    public StoredPack require(String coordinate) throws IOException {
        CompatibilityPack.identifier(coordinate.substring(0, coordinate.lastIndexOf('@')), "pack.id");
        return locked(() -> {
            StoredPack value = readIndex().packs().get(coordinate);
            if (value == null) throw new IOException("PACK_NOT_FOUND");
            return value;
        });
    }

    public CompatibilityPack load(String coordinate) throws IOException {
        StoredPack record = require(coordinate);
        Path archive = objectPath(record.contentHash());
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                || !CompatibilityPackLoader.sha256(archive).equals(record.contentHash())) {
            throw new IOException("PACK_OBJECT_CORRUPT");
        }
        CompatibilityPack pack = new CompatibilityPackLoader().load(archive);
        if (!pack.manifest().coordinate().equals(coordinate)) throw new IOException("PACK_IDENTITY_MISMATCH");
        return pack;
    }

    StoreIndex snapshot() throws IOException {
        return locked(this::readIndex);
    }

    StoredPack install(Path archive, String source, String operationId) throws IOException {
        CompatibilityPack pack = new CompatibilityPackLoader().load(archive);
        String coordinate = pack.manifest().coordinate();
        return mutate("install", operationId, index -> {
            StoredPack existing = index.packs().get(coordinate);
            if (existing != null) {
                if (!existing.contentHash().equals(pack.contentHash())) {
                    throw new IOException("PACK_COORDINATE_CONTENT_CONFLICT");
                }
                return new Mutation<>(index, existing);
            }
            Path object = objectPath(pack.contentHash());
            if (!Files.exists(object)) {
                Path temporary = Files.createTempFile(objects, ".pack-", ".tmp");
                Files.copy(archive, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!CompatibilityPackLoader.sha256(temporary).equals(pack.contentHash())) {
                    Files.deleteIfExists(temporary);
                    throw new IOException("PACK_COPY_HASH_MISMATCH");
                }
                atomicMove(temporary, object);
            }
            faults.at(Phase.AFTER_OBJECT_WRITE);
            StoredPack stored = new StoredPack(pack.manifest().id(), pack.manifest().version(),
                    pack.manifest().type(), pack.contentHash(), CompatibilityPack.PackState.STAGING,
                    source == null ? "external" : bounded(source, 256), List.of(), "",
                    clock.instant().toString(), "", false);
            return new Mutation<>(index.with(stored), stored);
        });
    }

    StoredPack recordEvidence(String coordinate, Evidence evidence, String operationId) throws IOException {
        return mutate("record-evidence", operationId, index -> {
            StoredPack current = requireMutable(index, coordinate);
            if (current.state() == CompatibilityPack.PackState.ACTIVE
                    || current.state() == CompatibilityPack.PackState.REVOKED) {
                throw new IOException("EVIDENCE_STATE_INVALID");
            }
            List<Evidence> values = new ArrayList<>(current.evidence());
            if (values.size() >= 128) values.removeFirst();
            values.add(evidence);
            CompatibilityPack.PackState state = evidence.passed()
                    ? CompatibilityPack.PackState.TESTED : CompatibilityPack.PackState.QUARANTINED;
            StoredPack updated = current.withState(state, clock.instant().toString()).withEvidence(
                    values, clock.instant().toString());
            return new Mutation<>(index.with(updated), updated);
        });
    }

    StoredPack indexVerified(String coordinate, String operationId) throws IOException {
        return mutate("index", operationId, index -> {
            StoredPack current = requireMutable(index, coordinate);
            boolean validEvidence = current.evidence().stream().anyMatch(value -> value.passed()
                    && (value.level() == CompatibilityPack.MatchLevel.EXACT_VERIFIED
                    || value.level() == CompatibilityPack.MatchLevel.RANGE_VERIFIED));
            if (current.state() != CompatibilityPack.PackState.TESTED || !validEvidence) {
                throw new IOException("VERIFICATION_EVIDENCE_REQUIRED");
            }
            StoredPack updated = current.withState(
                    CompatibilityPack.PackState.VERIFIED, clock.instant().toString());
            return new Mutation<>(index.with(updated), updated);
        });
    }

    StoredPack activate(String coordinate, EnvironmentFingerprint environment,
                        HealthCheck health, String operationId) throws IOException {
        return mutate("activate", operationId, index -> {
            StoredPack current = requireMutable(index, coordinate);
            if (current.state() != CompatibilityPack.PackState.VERIFIED
                    && current.state() != CompatibilityPack.PackState.DISABLED
                    && current.state() != CompatibilityPack.PackState.SUPERSEDED) {
                throw new IOException("PACK_NOT_ACTIVATABLE");
            }
            CompatibilityPack pack = loadFromRecord(current);
            for (String dependency : pack.manifest().dependencies()) {
                if (index.packs().values().stream().noneMatch(value ->
                        value.packId().equals(dependency) && value.state() == CompatibilityPack.PackState.ACTIVE)) {
                    throw new IOException("PACK_DEPENDENCY_MISSING:" + dependency);
                }
            }
            for (String conflict : pack.manifest().conflicts()) {
                if (index.packs().values().stream().anyMatch(value ->
                        value.packId().equals(conflict) && value.state() == CompatibilityPack.PackState.ACTIVE)) {
                    throw new IOException("PACK_CONFLICT:" + conflict);
                }
            }
            LinkedHashMap<String, StoredPack> packs = new LinkedHashMap<>(index.packs());
            packs.replaceAll((key, value) -> value.packId().equals(current.packId())
                    && value.state() == CompatibilityPack.PackState.ACTIVE
                    ? value.withState(CompatibilityPack.PackState.SUPERSEDED, clock.instant().toString()) : value);
            StoredPack active = current.withActivation(environment.digest(), clock.instant().toString())
                    .withState(CompatibilityPack.PackState.ACTIVE, clock.instant().toString());
            packs.put(coordinate, active);
            StoreIndex candidate = new StoreIndex(Map.copyOf(packs), index.revision() + 1);
            if (!health.healthy(active, pack, environment)) {
                throw new IOException("PACK_HEALTH_CHECK_FAILED");
            }
            return new Mutation<>(candidate, active);
        });
    }

    StoredPack changeState(String coordinate, CompatibilityPack.PackState state,
                           String operationId) throws IOException {
        if (!Set.of(CompatibilityPack.PackState.DISABLED, CompatibilityPack.PackState.QUARANTINED,
                CompatibilityPack.PackState.REVOKED).contains(state)) {
            throw new IllegalArgumentException("UNSUPPORTED_STATE_TRANSITION");
        }
        return mutate("state-" + state.name().toLowerCase(), operationId, index -> {
            StoredPack current = requireMutable(index, coordinate);
            StoredPack updated = current.withState(state, clock.instant().toString());
            return new Mutation<>(index.with(updated), updated);
        });
    }

    StoredPack rollback(String packId, String operationId) throws IOException {
        CompatibilityPack.identifier(packId, "pack.id");
        return mutate("rollback", operationId, index -> {
            List<StoredPack> candidates = index.packs().values().stream()
                    .filter(value -> value.packId().equals(packId)
                            && (value.state() == CompatibilityPack.PackState.SUPERSEDED
                            || value.state() == CompatibilityPack.PackState.DISABLED
                            || value.state() == CompatibilityPack.PackState.VERIFIED))
                    .sorted(Comparator.comparing(StoredPack::updatedAt).reversed()).toList();
            if (candidates.isEmpty()) throw new IOException("ROLLBACK_POINT_NOT_FOUND");
            String now = clock.instant().toString();
            StoredPack selected = candidates.getFirst().withState(CompatibilityPack.PackState.ACTIVE, now);
            LinkedHashMap<String, StoredPack> packs = new LinkedHashMap<>(index.packs());
            packs.replaceAll((key, value) -> value.packId().equals(packId)
                    && value.state() == CompatibilityPack.PackState.ACTIVE
                    ? value.withState(CompatibilityPack.PackState.SUPERSEDED, now) : value);
            packs.put(selected.coordinate(), selected);
            return new Mutation<>(new StoreIndex(Map.copyOf(packs), index.revision() + 1), selected);
        });
    }

    void remove(String coordinate, String operationId) throws IOException {
        mutate("remove", operationId, index -> {
            StoredPack current = requireMutable(index, coordinate);
            if (current.state() == CompatibilityPack.PackState.ACTIVE) {
                throw new IOException("ACTIVE_PACK_CANNOT_BE_REMOVED");
            }
            LinkedHashMap<String, StoredPack> packs = new LinkedHashMap<>(index.packs());
            packs.remove(coordinate);
            return new Mutation<>(new StoreIndex(Map.copyOf(packs), index.revision() + 1), null);
        });
    }

    Path export(String coordinate, Path destination) throws IOException {
        StoredPack record = require(coordinate);
        Path target = destination.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new IOException("EXPORT_SYMLINK_REJECTED");
        }
        Files.createDirectories(target.getParent());
        Files.copy(objectPath(record.contentHash()), target, StandardCopyOption.REPLACE_EXISTING);
        if (!CompatibilityPackLoader.sha256(target).equals(record.contentHash())) {
            Files.deleteIfExists(target);
            throw new IOException("EXPORT_HASH_MISMATCH");
        }
        return target;
    }

    public void recover() throws IOException {
        locked(() -> {
            recoverLocked();
            rebuildStateMarkers(readIndex());
            return null;
        });
    }

    private <T> T mutate(String operation, String operationId, Mutator<T> mutator) throws IOException {
        return locked(() -> {
            recoverLocked();
            StoreIndex before = readIndex();
            String rollbackId = UUID.randomUUID().toString();
            Path rollbackDirectory = root.resolve("rollback").resolve(rollbackId);
            Files.createDirectories(rollbackDirectory);
            Files.copy(indexFile, rollbackDirectory.resolve("active-index.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(signatureFile, rollbackDirectory.resolve("active-index.sig"),
                    StandardCopyOption.REPLACE_EXISTING);
            writeJournal(operation, operationId, rollbackId, "BEGIN");
            try {
                Mutation<T> mutation = mutator.apply(before);
                writeCandidateIndex(mutation.index());
                writeJournal(operation, operationId, rollbackId, "CANDIDATE_WRITTEN");
                faults.at(Phase.AFTER_CANDIDATE_INDEX);
                activateCandidateIndex();
                writeJournal(operation, operationId, rollbackId, "INDEX_SWITCHED");
                faults.at(Phase.AFTER_INDEX_SWITCH);
                rebuildStateMarkers(mutation.index());
                Files.deleteIfExists(journalFile);
                return mutation.result();
            } catch (IOException | RuntimeException failure) {
                restoreRollback(rollbackDirectory);
                Files.deleteIfExists(journalFile);
                rebuildStateMarkers(readIndex());
                throw failure;
            }
        });
    }

    private void recoverLocked() throws IOException {
        if (!Files.isRegularFile(journalFile, LinkOption.NOFOLLOW_LINKS)) return;
        JsonNode journal = JSON.readTree(Files.readAllBytes(journalFile));
        String rollbackId = journal.path("rollbackId").asText("");
        if (!rollbackId.matches("[a-f0-9-]{36}")) throw new IOException("TRANSACTION_JOURNAL_CORRUPT");
        restoreRollback(root.resolve("rollback").resolve(rollbackId));
        Files.deleteIfExists(root.resolve("index/candidate-index.json"));
        Files.deleteIfExists(root.resolve("index/candidate-index.sig"));
        Files.deleteIfExists(journalFile);
    }

    private void restoreRollback(Path directory) throws IOException {
        Path oldIndex = directory.resolve("active-index.json");
        Path oldSignature = directory.resolve("active-index.sig");
        if (!Files.isRegularFile(oldIndex) || !Files.isRegularFile(oldSignature)) {
            throw new IOException("ROLLBACK_SNAPSHOT_MISSING");
        }
        atomicCopy(oldIndex, indexFile);
        atomicCopy(oldSignature, signatureFile);
        readIndex();
    }

    private CompatibilityPack loadFromRecord(StoredPack record) throws IOException {
        Path archive = objectPath(record.contentHash());
        CompatibilityPack pack = new CompatibilityPackLoader().load(archive);
        if (!pack.manifest().coordinate().equals(record.coordinate())
                || !pack.contentHash().equals(record.contentHash())) {
            throw new IOException("PACK_OBJECT_IDENTITY_MISMATCH");
        }
        return pack;
    }

    private Path objectPath(String hash) {
        String safe = CompatibilityPack.hashOrEmpty(hash, "contentHash");
        if (safe.isEmpty()) throw new IllegalArgumentException("contentHash required");
        return objects.resolve(safe + ".mcac-compat");
    }

    private StoreIndex readIndex() throws IOException {
        byte[] bytes = Files.readAllBytes(indexFile);
        byte[] signature = Base64.getDecoder().decode(Files.readString(signatureFile).strip());
        if (!java.security.MessageDigest.isEqual(signature, sign(bytes))) {
            throw new IOException("ACTIVE_INDEX_SIGNATURE_INVALID");
        }
        JsonNode root = JSON.readTree(bytes);
        if (root.path("schemaVersion").asInt() != 1) throw new IOException("INDEX_SCHEMA_INVALID");
        LinkedHashMap<String, StoredPack> packs = new LinkedHashMap<>();
        for (JsonNode value : root.path("packs")) {
            StoredPack pack = stored(value);
            if (packs.putIfAbsent(pack.coordinate(), pack) != null) {
                throw new IOException("INDEX_DUPLICATE_PACK");
            }
        }
        return new StoreIndex(Map.copyOf(packs), root.path("revision").asLong());
    }

    private void writeIndex(StoreIndex index) throws IOException {
        byte[] bytes = indexBytes(index);
        atomicWrite(indexFile, bytes);
        atomicWrite(signatureFile, Base64.getEncoder().encode(sign(bytes)));
    }

    private void writeCandidateIndex(StoreIndex index) throws IOException {
        byte[] bytes = indexBytes(index);
        atomicWrite(root.resolve("index/candidate-index.json"), bytes);
        atomicWrite(root.resolve("index/candidate-index.sig"),
                Base64.getEncoder().encode(sign(bytes)));
    }

    private void activateCandidateIndex() throws IOException {
        atomicMove(root.resolve("index/candidate-index.json"), indexFile);
        atomicMove(root.resolve("index/candidate-index.sig"), signatureFile);
        readIndex();
    }

    private static byte[] indexBytes(StoreIndex index) throws IOException {
        ObjectNode root = JSON.createObjectNode().put("schemaVersion", 1).put("revision", index.revision());
        ArrayNode packs = root.putArray("packs");
        index.packs().values().stream().sorted(Comparator.comparing(StoredPack::coordinate))
                .forEach(value -> packs.add(json(value)));
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    private void rebuildStateMarkers(StoreIndex index) throws IOException {
        for (CompatibilityPack.PackState state : STATE_DIRECTORIES) {
            Path directory = root.resolve(state.name().toLowerCase(java.util.Locale.ROOT));
            try (var files = Files.newDirectoryStream(directory, "*.json")) {
                for (Path file : files) Files.deleteIfExists(file);
            }
        }
        for (StoredPack pack : index.packs().values()) {
            if (!STATE_DIRECTORIES.contains(pack.state())) continue;
            Path marker = root.resolve(pack.state().name().toLowerCase(java.util.Locale.ROOT))
                    .resolve(pack.coordinate().replace('@', '-') + ".json");
            atomicWrite(marker, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(json(pack)));
        }
    }

    private void writeJournal(String operation, String operationId, String rollbackId, String phase)
            throws IOException {
        ObjectNode value = JSON.createObjectNode().put("schemaVersion", 1)
                .put("operation", bounded(operation, 64))
                .put("operationId", bounded(operationId, 128))
                .put("rollbackId", rollbackId).put("phase", phase)
                .put("updatedAt", clock.instant().toString());
        atomicWrite(journalFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    private byte[] loadOrCreateKey() throws IOException {
        if (!Files.exists(keyFile)) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            atomicWrite(keyFile, Base64.getEncoder().encode(key));
            try {
                Files.setPosixFilePermissions(keyFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows ACLs are inherited from the per-user control directory.
            }
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(Files.readAllBytes(keyFile));
        } catch (IllegalArgumentException malformed) {
            throw new IOException("STORE_SIGNING_KEY_INVALID", malformed);
        }
        if (key.length != 32) throw new IOException("STORE_SIGNING_KEY_INVALID");
        return key;
    }

    private byte[] sign(byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private <T> T locked(IoOperation<T> operation) throws IOException {
        localLock.lock();
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return operation.run();
        } finally {
            localLock.unlock();
        }
    }

    private static void atomicWrite(Path destination, byte[] bytes) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".compat-", ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        atomicMove(temporary, destination);
    }

    private static void atomicCopy(Path source, Path destination) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".compat-copy-", ".tmp");
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
        atomicMove(temporary, destination);
    }

    private static void atomicMove(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static StoredPack requireMutable(StoreIndex index, String coordinate) throws IOException {
        StoredPack value = index.packs().get(coordinate);
        if (value == null) throw new IOException("PACK_NOT_FOUND");
        return value;
    }

    private static ObjectNode json(StoredPack value) {
        ObjectNode node = JSON.createObjectNode().put("packId", value.packId())
                .put("version", value.version()).put("type", value.type().name())
                .put("contentHash", value.contentHash()).put("state", value.state().name())
                .put("source", value.source()).put("activationFingerprint", value.activationFingerprint())
                .put("updatedAt", value.updatedAt()).put("activatedAt", value.activatedAt())
                .put("nativeExecutionAvailable", value.nativeExecutionAvailable());
        ArrayNode evidence = node.putArray("evidence");
        value.evidence().forEach(item -> evidence.add(JSON.createObjectNode()
                .put("evidenceId", item.evidenceId()).put("kind", item.kind())
                .put("level", item.level().name()).put("passed", item.passed())
                .put("recordedAt", item.recordedAt()).put("summary", item.summary())
                .put("artifactHash", item.artifactHash())));
        return node;
    }

    private static StoredPack stored(JsonNode node) {
        List<Evidence> evidence = new ArrayList<>();
        node.path("evidence").forEach(item -> evidence.add(new Evidence(
                item.path("evidenceId").asText(), item.path("kind").asText(),
                CompatibilityPack.MatchLevel.valueOf(item.path("level").asText()),
                item.path("passed").asBoolean(), item.path("recordedAt").asText(),
                item.path("summary").asText(), item.path("artifactHash").asText())));
        return new StoredPack(node.path("packId").asText(), node.path("version").asText(),
                CompatibilityPack.PackType.valueOf(node.path("type").asText()),
                node.path("contentHash").asText(),
                CompatibilityPack.PackState.valueOf(node.path("state").asText()),
                node.path("source").asText(), evidence,
                node.path("activationFingerprint").asText(), node.path("updatedAt").asText(),
                node.path("activatedAt").asText(), node.path("nativeExecutionAvailable").asBoolean());
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException("INVALID_TRANSACTION_METADATA");
        }
        return value;
    }

    public record Evidence(String evidenceId, String kind, CompatibilityPack.MatchLevel level,
                           boolean passed, String recordedAt, String summary, String artifactHash) {
        public Evidence {
            evidenceId = CompatibilityPack.identifier(evidenceId, "evidenceId");
            kind = CompatibilityPack.bounded(kind, "evidence.kind", 1, 64);
            level = java.util.Objects.requireNonNull(level, "evidence.level");
            Instant.parse(recordedAt);
            summary = CompatibilityPack.bounded(summary, "evidence.summary", 1, 512);
            artifactHash = CompatibilityPack.hashOrEmpty(artifactHash, "evidence.artifactHash");
        }
    }

    public record StoredPack(
            String packId, String version, CompatibilityPack.PackType type, String contentHash,
            CompatibilityPack.PackState state, String source, List<Evidence> evidence,
            String activationFingerprint, String updatedAt, String activatedAt,
            boolean nativeExecutionAvailable) {
        public StoredPack {
            packId = CompatibilityPack.identifier(packId, "pack.id");
            version = CompatibilityPack.version(version);
            type = java.util.Objects.requireNonNull(type, "type");
            contentHash = CompatibilityPack.hashOrEmpty(contentHash, "contentHash");
            state = java.util.Objects.requireNonNull(state, "state");
            source = CompatibilityPack.bounded(source, "source", 1, 256);
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
            if (evidence.size() > 128) throw new IllegalArgumentException("TOO_MUCH_EVIDENCE");
            activationFingerprint = CompatibilityPack.hashOrEmpty(
                    activationFingerprint, "activationFingerprint");
            Instant.parse(updatedAt);
            if (!activatedAt.isEmpty()) Instant.parse(activatedAt);
        }

        @JsonProperty("coordinate")
        public String coordinate() {
            return packId + '@' + version;
        }

        StoredPack withState(CompatibilityPack.PackState value, String at) {
            return new StoredPack(packId, version, type, contentHash, value, source, evidence,
                    activationFingerprint, at, activatedAt, nativeExecutionAvailable);
        }

        StoredPack withEvidence(List<Evidence> values, String at) {
            return new StoredPack(packId, version, type, contentHash, state, source, values,
                    activationFingerprint, at, activatedAt, nativeExecutionAvailable);
        }

        StoredPack withActivation(String fingerprint, String at) {
            return new StoredPack(packId, version, type, contentHash, state, source, evidence,
                    fingerprint, at, at, nativeExecutionAvailable);
        }
    }

    record StoreIndex(Map<String, StoredPack> packs, long revision) {
        StoreIndex {
            packs = Map.copyOf(packs);
            if (packs.size() > 4096 || revision < 0) throw new IllegalArgumentException("INDEX_LIMIT");
        }

        static StoreIndex empty() {
            return new StoreIndex(Map.of(), 0);
        }

        StoreIndex with(StoredPack pack) {
            LinkedHashMap<String, StoredPack> values = new LinkedHashMap<>(packs);
            values.put(pack.coordinate(), pack);
            return new StoreIndex(Map.copyOf(values), revision + 1);
        }
    }

    @FunctionalInterface
    public interface HealthCheck {
        boolean healthy(StoredPack record, CompatibilityPack pack, EnvironmentFingerprint environment)
                throws IOException;
    }

    @FunctionalInterface
    interface FaultInjector {
        void at(Phase phase) throws IOException;
    }

    enum Phase { AFTER_OBJECT_WRITE, AFTER_CANDIDATE_INDEX, AFTER_INDEX_SWITCH }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }

    @FunctionalInterface
    private interface Mutator<T> {
        Mutation<T> apply(StoreIndex index) throws IOException;
    }

    private record Mutation<T>(StoreIndex index, T result) {}
}
