package com.mccompanion.runtime.workspace;

import com.mccompanion.runtime.security.Digests;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Read-only classpath catalog for compatibility wrappers expressed as declarative Task Graphs. */
public final class BuiltinSkillCatalog {
    private static final List<String> IDS = List.of(
            "collect_resource", "mine_vein", "smelt_item", "withdraw_storage", "craft_item", "defend_owner");
    private final Map<String, BuiltinSkill> skills;

    public BuiltinSkillCatalog() {
        this.skills = IDS.stream().map(BuiltinSkillCatalog::load)
                .collect(Collectors.toUnmodifiableMap(BuiltinSkill::skillId, value -> value));
    }

    public Optional<BuiltinSkill> get(String skillId) {
        return Optional.ofNullable(skills.get(skillId));
    }

    public List<BuiltinSkill> list() {
        return skills.values().stream().sorted(java.util.Comparator.comparing(BuiltinSkill::skillId)).toList();
    }

    private static BuiltinSkill load(String skillId) {
        String resource = "/skills/builtin/" + skillId + ".yaml";
        try (InputStream input = BuiltinSkillCatalog.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing built-in Skill resource " + resource);
            byte[] bytes = input.readNBytes(65_537);
            if (bytes.length == 0 || bytes.length > 65_536 || input.read() != -1) {
                throw new IllegalStateException("Built-in Skill resource is empty or oversized: " + resource);
            }
            String document = new String(bytes, StandardCharsets.UTF_8);
            return new BuiltinSkill(skillId, "yaml", document, Digests.sha256(document), "BUILT_IN");
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to load built-in Skill " + resource, failure);
        }
    }

    public record BuiltinSkill(String skillId, String format, String document, String sha256, String trust) {
    }
}
