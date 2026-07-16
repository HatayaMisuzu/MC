package com.mccompanion.runtime.capability;

import com.mccompanion.runtime.agent.RiskLevel;
import com.mccompanion.runtime.json.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CapabilityRegistry {
    private final Map<String, CapabilityDefinition> definitions;

    public CapabilityRegistry(List<CapabilityDefinition> values) {
        LinkedHashMap<String, CapabilityDefinition> copy = new LinkedHashMap<>();
        for (CapabilityDefinition value : values) {
            if (copy.putIfAbsent(value.name(), value) != null) throw new IllegalArgumentException("Duplicate capability " + value.name());
        }
        definitions = Map.copyOf(copy);
    }

    public static CapabilityRegistry standard() {
        return new CapabilityRegistry(List.of(
                implemented("NavigateTo", RiskLevel.LOW), implemented("FollowOwner", RiskLevel.LOW),
                implemented("ExploreArea", RiskLevel.MEDIUM), implemented("LocateKnownContainer", RiskLevel.LOW),
                implemented("WithdrawFromStorage", RiskLevel.LOW), implemented("DepositToStorage", RiskLevel.LOW),
                capability("CollectResource", RiskLevel.MEDIUM), capability("MineResourceVein", RiskLevel.MEDIUM),
                implemented("CraftItem", RiskLevel.LOW), capability("SmeltItem", RiskLevel.LOW),
                implemented("DeliverItem", RiskLevel.LOW), implemented("EatAndRecover", RiskLevel.LOW),
                capability("DefendOwner", RiskLevel.MEDIUM), capability("RetreatFromDanger", RiskLevel.LOW),
                capability("BuildSmallBlueprint", RiskLevel.HIGH)));
    }

    private static CapabilityDefinition capability(String name, RiskLevel risk) {
        return new CapabilityDefinition(name, "Reusable Minecraft survival capability", Json.object(), risk, true, true, false);
    }

    private static CapabilityDefinition implemented(String name, RiskLevel risk) {
        return new CapabilityDefinition(name, "Reusable Minecraft survival capability", Json.object(), risk, true, true, true);
    }

    public Optional<CapabilityDefinition> find(String name) { return Optional.ofNullable(definitions.get(name)); }
    public List<String> names() { return definitions.keySet().stream().sorted().toList(); }
    public List<CapabilityDefinition> definitions() { return definitions.values().stream()
            .sorted(java.util.Comparator.comparing(CapabilityDefinition::name)).toList(); }
}
