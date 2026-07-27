package com.mccompanion.compat;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic matching and precedence composition; it does not create goals or plans. */
public final class CompatibilityResolver {
    public Resolution resolve(EnvironmentFingerprint environment, List<Candidate> installed) {
        List<Candidate> all = new ArrayList<>(installed == null ? List.of() : installed);
        for (CompatibilityPack builtin : BuiltinBridgeProviders.all()) {
            all.add(new Candidate(builtin, CompatibilityPack.PackState.ACTIVE, "", true));
        }
        List<MatchedPack> matches = all.stream()
                .map(candidate -> match(environment, candidate))
                .filter(value -> value.level() != CompatibilityPack.MatchLevel.INCOMPATIBLE)
                .sorted(Comparator.comparingInt((MatchedPack value) -> precedence(value.pack().manifest().type()))
                        .thenComparingInt(value -> value.pack().manifest().precedence())
                        .thenComparing(value -> value.pack().manifest().id())
                        .thenComparing(value -> value.pack().manifest().version()))
                .toList();
        LinkedHashMap<String, CompatibilityPack.Capability> capabilities = new LinkedHashMap<>();
        LinkedHashSet<String> conflicts = new LinkedHashSet<>();
        LinkedHashSet<String> suppressions = new LinkedHashSet<>();
        Set<String> selectedIds = matches.stream().map(value -> value.pack().manifest().id())
                .collect(java.util.stream.Collectors.toSet());
        for (MatchedPack matched : matches) {
            CompatibilityPack pack = matched.pack();
            for (String conflict : pack.manifest().conflicts()) {
                if (selectedIds.contains(conflict)) conflicts.add(pack.manifest().id() + "<->" + conflict);
            }
            for (CompatibilityPack.Capability capability : capabilities(pack)) {
                boolean unsafeMatch = matched.level() == CompatibilityPack.MatchLevel.PROVISIONAL
                        || matched.level() == CompatibilityPack.MatchLevel.STRUCTURAL_MATCH
                        || matched.stale();
                if (unsafeMatch && capability.risk().ordinal() >= CompatibilityPack.Risk.HIGH.ordinal()) {
                    String reason = matched.stale() ? "STALE_FINGERPRINT" : "MATCH_NOT_VERIFIED";
                    capabilities.put(capability.id(), new CompatibilityPack.Capability(
                            capability.id(), capability.kind(), capability.risk(), false,
                            capability.contract(), capability.sourcePack(), reason));
                    suppressions.add(capability.id() + ':' + reason);
                } else {
                    CompatibilityPack.Capability prior = capabilities.put(capability.id(), capability);
                    if (prior != null) suppressions.add(prior.id() + ":OVERRIDDEN_BY:" + capability.sourcePack());
                }
            }
        }
        if (!conflicts.isEmpty()) {
            capabilities.replaceAll((id, capability) ->
                    capability.risk().ordinal() >= CompatibilityPack.Risk.HIGH.ordinal()
                            ? new CompatibilityPack.Capability(capability.id(), capability.kind(),
                            capability.risk(), false, capability.contract(), capability.sourcePack(),
                            "UNRESOLVED_PACK_CONFLICT") : capability);
        }
        return new Resolution(environment, matches, Map.copyOf(capabilities),
                List.copyOf(conflicts), List.copyOf(suppressions));
    }

    public MatchedPack match(EnvironmentFingerprint environment, Candidate candidate) {
        CompatibilityPack.Target target = candidate.pack().manifest().target();
        if (!target.instanceId().isEmpty() && !target.instanceId().equals(environment.instanceId())
                || !target.minecraftExact().isEmpty()
                && !target.minecraftExact().equals(environment.minecraftVersion())
                || !target.loaderType().isEmpty()
                && !target.loaderType().equalsIgnoreCase(environment.loaderType())
                || !versionMatches(target.minecraftRange(), environment.minecraftVersion())
                || !versionMatches(target.loaderVersionRange(), environment.loaderVersion())
                || target.javaMinimum() != null && environment.javaMajor() < target.javaMinimum()
                || target.javaMaximum() != null && environment.javaMajor() > target.javaMaximum()) {
            return new MatchedPack(candidate.pack(), CompatibilityPack.MatchLevel.INCOMPATIBLE,
                    false, candidate.builtin(), "TARGET_MISMATCH");
        }
        boolean exact = !target.minecraftExact().isEmpty();
        boolean structural = false;
        for (Map.Entry<String, CompatibilityPack.ModTarget> requirement : target.mods().entrySet()) {
            EnvironmentFingerprint.ModFingerprint actual = environment.mods().get(requirement.getKey());
            if (actual == null) {
                return new MatchedPack(candidate.pack(), CompatibilityPack.MatchLevel.INCOMPATIBLE,
                        false, candidate.builtin(), "MOD_MISSING:" + requirement.getKey());
            }
            if (!versionMatches(requirement.getValue().versionRange(), actual.version())) {
                return new MatchedPack(candidate.pack(), CompatibilityPack.MatchLevel.INCOMPATIBLE,
                        false, candidate.builtin(), "MOD_VERSION_MISMATCH:" + requirement.getKey());
            }
            if (!requirement.getValue().jarHash().isEmpty()
                    && !requirement.getValue().jarHash().equals(actual.jarHash())) {
                structural = true;
            }
        }
        boolean fingerprintExact = hashesMatch(target.configurationHash(), environment.configurationHash())
                && hashesMatch(target.scriptsHash(), environment.scriptsHash())
                && hashesMatch(target.dataPacksHash(), environment.dataPacksHash())
                && hashesMatch(target.modpackHash(), environment.modpackHash());
        boolean stale = candidate.state() == CompatibilityPack.PackState.ACTIVE
                && !candidate.activationFingerprint().isEmpty()
                && !candidate.activationFingerprint().equals(environment.digest());
        CompatibilityPack.MatchLevel level;
        if (structural) level = CompatibilityPack.MatchLevel.STRUCTURAL_MATCH;
        else if (exact && fingerprintExact) level = CompatibilityPack.MatchLevel.EXACT_VERIFIED;
        else if (!target.minecraftRange().isEmpty() || !target.loaderVersionRange().isEmpty()) {
            level = CompatibilityPack.MatchLevel.RANGE_VERIFIED;
        } else if (candidate.builtin()) level = CompatibilityPack.MatchLevel.EXACT_VERIFIED;
        else level = CompatibilityPack.MatchLevel.PROVISIONAL;
        return new MatchedPack(candidate.pack(), level, stale, candidate.builtin(),
                stale ? "ACTIVATION_FINGERPRINT_CHANGED" : "MATCHED");
    }

    private static List<CompatibilityPack.Capability> capabilities(CompatibilityPack pack) {
        List<CompatibilityPack.Capability> values = new ArrayList<>();
        pack.documents().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("capabilities/"))
                .forEach(entry -> {
                    JsonNode source = entry.getValue().path("capabilities");
                    if (!source.isArray()) return;
                    for (JsonNode capability : source) {
                        String id = capability.path("id").asText("");
                        String kind = capability.path("kind").asText("declarative");
                        CompatibilityPack.Risk risk;
                        try {
                            risk = CompatibilityPack.Risk.valueOf(
                                    capability.path("risk").asText("LOW").toUpperCase(java.util.Locale.ROOT));
                        } catch (IllegalArgumentException invalid) {
                            continue;
                        }
                        if (!id.matches("[a-z0-9][a-z0-9._-]{0,127}")) continue;
                        values.add(new CompatibilityPack.Capability(id, kind, risk,
                                capability.path("enabled").asBoolean(true),
                                capability.path("contract"), pack.manifest().coordinate(), ""));
                    }
                });
        return values;
    }

    private static boolean hashesMatch(String expected, String actual) {
        return expected.isEmpty() || expected.equals(actual);
    }

    /**
     * Bounded Maven-style interval matching for dotted numeric versions. A non-interval value is
     * an exact version. This deliberately rejects unknown syntax instead of guessing compatibility.
     */
    static boolean versionMatches(String expression, String actual) {
        if (expression == null || expression.isBlank()) return true;
        String range = expression.strip();
        if (!(range.startsWith("[") || range.startsWith("("))) return range.equals(actual);
        if (!(range.endsWith("]") || range.endsWith(")")) || range.length() < 3
                || range.chars().filter(value -> value == ',').count() != 1) return false;
        String[] bounds = range.substring(1, range.length() - 1).split(",", -1);
        Integer lower = bounds[0].isBlank() ? null : compareVersions(actual, bounds[0].strip());
        Integer upper = bounds[1].isBlank() ? null : compareVersions(actual, bounds[1].strip());
        if (lower != null && (lower < 0 || lower == 0 && range.charAt(0) == '(')) return false;
        return upper == null || upper < 0 || upper == 0 && range.charAt(range.length() - 1) == ']';
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("[-+]", 2)[0].split("\\.");
        String[] b = right.split("[-+]", 2)[0].split("\\.");
        if (a.length > 8 || b.length > 8) return left.compareTo(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String av = i < a.length ? a[i] : "0";
            String bv = i < b.length ? b[i] : "0";
            if (!av.matches("\\d{1,9}") || !bv.matches("\\d{1,9}")) return left.compareTo(right);
            int comparison = Integer.compare(Integer.parseInt(av), Integer.parseInt(bv));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static int precedence(CompatibilityPack.PackType type) {
        return switch (type) {
            case MINECRAFT_BASE -> 0;
            case LOADER -> 10;
            case MOD -> 20;
            case PATCH -> 25;
            case MODPACK_OVERLAY -> 30;
            case INSTANCE_LOCAL -> 40;
        };
    }

    public record Candidate(CompatibilityPack pack, CompatibilityPack.PackState state,
                            String activationFingerprint, boolean builtin) {}

    public record MatchedPack(CompatibilityPack pack, CompatibilityPack.MatchLevel level,
                              boolean stale, boolean builtin, String reason) {}

    public record Resolution(EnvironmentFingerprint environment, List<MatchedPack> matchedPacks,
                             Map<String, CompatibilityPack.Capability> capabilities,
                             List<String> conflicts, List<String> suppressions) {
        public CompatibilityPack.Capability requireCallable(String capabilityId) {
            CompatibilityPack.Capability value = capabilities.get(capabilityId);
            if (value == null) throw new IllegalArgumentException("CAPABILITY_NOT_RESOLVED");
            if (!value.enabled()) throw new IllegalStateException("CAPABILITY_SUPPRESSED:" + value.suppressionReason());
            return value;
        }
    }
}
