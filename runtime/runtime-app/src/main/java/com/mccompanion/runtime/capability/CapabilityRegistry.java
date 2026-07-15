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
                capability("NavigateTo", RiskLevel.LOW), capability("FollowOwner", RiskLevel.LOW),
                capability("ExploreArea", RiskLevel.MEDIUM), capability("LocateKnownContainer", RiskLevel.LOW),
                capability("WithdrawFromStorage", RiskLevel.LOW), capability("DepositToStorage", RiskLevel.LOW),
                capability("CollectResource", RiskLevel.MEDIUM), capability("MineResourceVein", RiskLevel.MEDIUM),
                capability("CraftItem", RiskLevel.LOW), capability("SmeltItem", RiskLevel.LOW),
                capability("DeliverItem", RiskLevel.LOW), capability("EatAndRecover", RiskLevel.LOW),
                capability("DefendOwner", RiskLevel.MEDIUM), capability("RetreatFromDanger", RiskLevel.LOW),
                capability("BuildSmallBlueprint", RiskLevel.HIGH)));
    }

    private static CapabilityDefinition capability(String name, RiskLevel risk) {
        return new CapabilityDefinition(name, "Reusable Minecraft survival capability", Json.object(), risk, true, true);
    }

    public Optional<CapabilityDefinition> find(String name) { return Optional.ofNullable(definitions.get(name)); }
    public List<String> names() { return definitions.keySet().stream().sorted().toList(); }
}
