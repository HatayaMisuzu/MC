package com.mccompanion.compat;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, fully validated declarative compatibility package. */
public record CompatibilityPack(
        Manifest manifest,
        String contentHash,
        Map<String, JsonNode> documents,
        Map<String, String> entryHashes) {

    public CompatibilityPack {
        manifest = java.util.Objects.requireNonNull(manifest, "manifest");
        contentHash = required(contentHash, "contentHash");
        documents = Map.copyOf(documents == null ? Map.of() : documents);
        entryHashes = Map.copyOf(entryHashes == null ? Map.of() : entryHashes);
    }

    public enum PackType {
        MINECRAFT_BASE, LOADER, MOD, MODPACK_OVERLAY, INSTANCE_LOCAL, PATCH;

        public static PackType parse(String value) {
            return valueOf(required(value, "pack.type").replace('-', '_').toUpperCase(java.util.Locale.ROOT));
        }
    }

    public enum PackState {
        STAGING, TESTED, VERIFIED, ACTIVE, DISABLED, QUARANTINED,
        SUPERSEDED, REVOKED, REMOVED, STALE
    }

    public enum MatchLevel {
        EXACT_VERIFIED, RANGE_VERIFIED, STRUCTURAL_MATCH, PROVISIONAL,
        INCOMPATIBLE, UNKNOWN
    }

    public enum Risk {
        LOW, MEDIUM, HIGH, CRITICAL;

        public boolean allows(Risk requested) {
            return requested.ordinal() <= ordinal();
        }
    }

    public record Manifest(
            String schemaVersion,
            String id,
            String version,
            PackType type,
            String displayName,
            String authorType,
            Instant createdAt,
            Target target,
            RuntimeDeclaration runtime,
            Permissions permissions,
            List<String> dependencies,
            List<String> conflicts,
            List<String> replaces,
            List<String> extendsPacks,
            List<String> patches,
            int precedence,
            List<String> limitations) {
        public Manifest {
            if (!"mcac-compat/1".equals(schemaVersion)) {
                throw new IllegalArgumentException("UNSUPPORTED_SCHEMA_VERSION");
            }
            id = identifier(id, "pack.id");
            version = CompatibilityPack.version(version);
            type = java.util.Objects.requireNonNull(type, "pack.type");
            displayName = bounded(displayName, "pack.displayName", 1, 128);
            authorType = bounded(authorType, "pack.authorType", 1, 64);
            createdAt = java.util.Objects.requireNonNull(createdAt, "pack.createdAt");
            target = java.util.Objects.requireNonNull(target, "target");
            runtime = java.util.Objects.requireNonNull(runtime, "runtime");
            permissions = java.util.Objects.requireNonNull(permissions, "permissions");
            dependencies = boundedIds(dependencies, 64, "dependencies");
            conflicts = boundedIds(conflicts, 64, "conflicts");
            replaces = boundedIds(replaces, 64, "replaces");
            extendsPacks = boundedIds(extendsPacks, 64, "extends");
            patches = boundedIds(patches, 64, "patches");
            if (precedence < -10_000 || precedence > 10_000) {
                throw new IllegalArgumentException("INVALID_PRECEDENCE");
            }
            limitations = boundedStrings(limitations, 64, 512, "limitations");
        }

        public String coordinate() {
            return id + '@' + version;
        }
    }

    public record Target(
            String minecraftExact,
            String minecraftRange,
            String loaderType,
            String loaderVersionRange,
            Integer javaMinimum,
            Integer javaMaximum,
            String instanceId,
            Map<String, ModTarget> mods,
            String configurationHash,
            String scriptsHash,
            String dataPacksHash,
            String modpackHash) {
        public Target {
            minecraftExact = optional(minecraftExact, 32, "target.minecraft.exact");
            minecraftRange = optional(minecraftRange, 64, "target.minecraft.range");
            loaderType = optional(loaderType, 32, "target.loader.type");
            loaderVersionRange = optional(loaderVersionRange, 64, "target.loader.versionRange");
            instanceId = optional(instanceId, 128, "target.instanceId");
            mods = Map.copyOf(mods == null ? Map.of() : mods);
            if (mods.size() > 512) throw new IllegalArgumentException("TOO_MANY_MOD_TARGETS");
            configurationHash = hashOrEmpty(configurationHash, "target.configurationHash");
            scriptsHash = hashOrEmpty(scriptsHash, "target.scriptsHash");
            dataPacksHash = hashOrEmpty(dataPacksHash, "target.dataPacksHash");
            modpackHash = hashOrEmpty(modpackHash, "target.modpackHash");
            if (javaMinimum != null && (javaMinimum < 8 || javaMinimum > 99)
                    || javaMaximum != null && (javaMaximum < 8 || javaMaximum > 99)
                    || javaMinimum != null && javaMaximum != null && javaMinimum > javaMaximum) {
                throw new IllegalArgumentException("INVALID_JAVA_RANGE");
            }
        }
    }

    public record ModTarget(String versionRange, String jarHash) {
        public ModTarget {
            versionRange = optional(versionRange, 64, "mod.versionRange");
            jarHash = hashOrEmpty(jarHash, "mod.jarHash");
        }
    }

    public record RuntimeDeclaration(
            int minimumHostVersion,
            boolean nativeCode,
            boolean hotReloadable,
            boolean restartRequired,
            String nativeProtocol,
            List<String> nativeArtifacts) {
        public RuntimeDeclaration {
            if (minimumHostVersion != 1) throw new IllegalArgumentException("HOST_VERSION_UNSUPPORTED");
            nativeProtocol = optional(nativeProtocol, 64, "runtime.nativeProtocol");
            nativeArtifacts = boundedStrings(nativeArtifacts, 32, 256, "runtime.nativeArtifacts");
            if (!nativeCode && (!nativeProtocol.isEmpty() || !nativeArtifacts.isEmpty())) {
                throw new IllegalArgumentException("NATIVE_DECLARATION_INCONSISTENT");
            }
        }
    }

    public record Permissions(Set<String> declared, Set<String> forbidden) {
        private static final Set<String> ALWAYS_FORBIDDEN = Set.of(
                "SHELL", "ARBITRARY_FILE_ACCESS", "DIRECT_WORLD_EDIT",
                "CREDENTIAL_READ", "PROCESS_EXEC", "NETWORK_UNBOUNDED");

        public Permissions {
            declared = boundedPermissions(declared, "permissions.declared");
            forbidden = boundedPermissions(forbidden, "permissions.forbidden");
            if (!java.util.Collections.disjoint(declared, ALWAYS_FORBIDDEN)) {
                throw new IllegalArgumentException("FORBIDDEN_PERMISSION_DECLARED");
            }
            if (!forbidden.containsAll(ALWAYS_FORBIDDEN)) {
                throw new IllegalArgumentException("REQUIRED_FORBIDDEN_PERMISSIONS_MISSING");
            }
        }
    }

    public record Capability(
            String id, String kind, Risk risk, boolean enabled,
            JsonNode contract, String sourcePack, String suppressionReason) {
        public Capability {
            id = identifier(id, "capability.id");
            kind = bounded(kind, "capability.kind", 1, 64);
            risk = java.util.Objects.requireNonNull(risk, "capability.risk");
            contract = contract == null ? com.fasterxml.jackson.databind.node.NullNode.instance : contract.deepCopy();
            sourcePack = required(sourcePack, "sourcePack");
            suppressionReason = suppressionReason == null ? "" : suppressionReason;
        }
    }

    private static Set<String> boundedPermissions(Set<String> values, String name) {
        Set<String> copy = Set.copyOf(values == null ? Set.of() : values);
        if (copy.size() > 64 || copy.stream().anyMatch(value ->
                value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}"))) {
            throw new IllegalArgumentException("INVALID_" + name.toUpperCase(java.util.Locale.ROOT));
        }
        return copy;
    }

    private static List<String> boundedIds(List<String> values, int max, String name) {
        List<String> copy = List.copyOf(values == null ? List.of() : values);
        if (copy.size() > max) throw new IllegalArgumentException("TOO_MANY_" + name.toUpperCase());
        copy.forEach(value -> identifier(value, name));
        return copy;
    }

    private static List<String> boundedStrings(List<String> values, int max, int length, String name) {
        List<String> copy = List.copyOf(values == null ? List.of() : values);
        if (copy.size() > max || copy.stream().anyMatch(value -> value == null || value.length() > length)) {
            throw new IllegalArgumentException("INVALID_" + name.toUpperCase());
        }
        return copy;
    }

    static String identifier(String value, String name) {
        String result = required(value, name);
        if (!result.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("INVALID_" + name.toUpperCase());
        }
        return result;
    }

    static String version(String value) {
        String result = required(value, "version");
        if (!result.matches("[0-9]+(?:\\.[0-9]+){0,3}(?:[-+][A-Za-z0-9.-]+)?")) {
            throw new IllegalArgumentException("INVALID_VERSION");
        }
        return result;
    }

    static String hashOrEmpty(String value, String name) {
        String result = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!result.isEmpty() && !result.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("INVALID_" + name.toUpperCase());
        }
        return result;
    }

    static String optional(String value, int max, String name) {
        if (value == null) return "";
        String result = value.strip();
        if (result.length() > max) throw new IllegalArgumentException("INVALID_" + name.toUpperCase());
        return result;
    }

    static String bounded(String value, String name, int minimum, int maximum) {
        String result = value == null ? "" : value.strip();
        if (result.length() < minimum || result.length() > maximum) {
            throw new IllegalArgumentException("INVALID_" + name.toUpperCase());
        }
        return result;
    }

    static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.strip();
    }
}
