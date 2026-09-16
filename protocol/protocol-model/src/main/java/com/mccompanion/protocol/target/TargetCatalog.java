package com.mccompanion.protocol.target;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Reads only the catalog shipped inside this build. No caller-selected URL or path. */
public final class TargetCatalog {
    private static final TargetCatalog BUNDLED = load();
    private final List<TargetDescriptor> targets;
    private TargetCatalog(List<TargetDescriptor> targets) {
        this.targets = List.copyOf(targets);
        if (targets.isEmpty() || targets.stream().map(TargetDescriptor::targetId).distinct().count() != targets.size()) {
            throw new IllegalArgumentException("Invalid target catalog");
        }
    }
    public static TargetCatalog bundled() { return BUNDLED; }
    public List<TargetDescriptor> targets() { return targets; }
    public Optional<TargetDescriptor> find(String minecraftVersion, String loader) {
        return targets.stream().filter(target -> target.matches(minecraftVersion, loader)).findFirst();
    }
    public Optional<TargetDescriptor> byId(String id) {
        return targets.stream().filter(target -> target.targetId().equals(id)).findFirst();
    }
    private static TargetCatalog load() {
        try (var input = TargetCatalog.class.getResourceAsStream("/mcac/catalog.json")) {
            if (input == null) throw new IllegalStateException("Missing bundled target catalog");
            var mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
            var document = mapper.readTree(input);
            if (document.path("schemaVersion").asInt() != 1 || !document.path("targets").isArray()) throw new IOException("Unsupported target catalog schema");
            return new TargetCatalog(mapper.readerForListOf(TargetDescriptor.class).readValue(document.path("targets")));
        } catch (IOException invalid) { throw new IllegalStateException("Invalid bundled target catalog", invalid); }
    }
}
