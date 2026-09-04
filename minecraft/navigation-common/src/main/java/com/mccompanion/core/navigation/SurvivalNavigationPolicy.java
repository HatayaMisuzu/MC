package com.mccompanion.core.navigation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Explicit, cumulative authority for one survival-navigation execution. */
public record SurvivalNavigationPolicy(
        int maxBreakBlocks,
        int maxPlaceBlocks,
        int maxRiskUnits,
        Set<String> allowedBreakBlocks,
        Set<String> allowedPlaceBlocks) {
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int MAX_BLOCK_TYPES = 16;

    public SurvivalNavigationPolicy {
        if (maxBreakBlocks < 0 || maxBreakBlocks > 8
                || maxPlaceBlocks < 0 || maxPlaceBlocks > 8
                || maxRiskUnits < 0 || maxRiskUnits > 16) {
            throw new IllegalArgumentException("navigation policy budget is outside safe bounds");
        }
        allowedBreakBlocks = normalized(allowedBreakBlocks, "allowedBreakBlocks");
        allowedPlaceBlocks = normalized(allowedPlaceBlocks, "allowedPlaceBlocks");
        if (maxBreakBlocks > 0 && allowedBreakBlocks.isEmpty()) {
            throw new IllegalArgumentException("break authority requires an explicit block allowlist");
        }
        if (maxPlaceBlocks > 0 && allowedPlaceBlocks.isEmpty()) {
            throw new IllegalArgumentException("place authority requires an explicit block allowlist");
        }
        if (maxBreakBlocks == 0 && !allowedBreakBlocks.isEmpty()) {
            throw new IllegalArgumentException("break allowlist requires a positive break budget");
        }
        if (maxPlaceBlocks == 0 && !allowedPlaceBlocks.isEmpty()) {
            throw new IllegalArgumentException("place allowlist requires a positive place budget");
        }
    }

    public static SurvivalNavigationPolicy locomotionOnly() {
        return new SurvivalNavigationPolicy(0, 0, 8, Set.of(), Set.of());
    }

    public static SurvivalNavigationPolicy breaking(
            int maxBreakBlocks, int maxRiskUnits, List<String> allowedBreakBlocks) {
        return new SurvivalNavigationPolicy(maxBreakBlocks, 0, maxRiskUnits,
                new LinkedHashSet<>(allowedBreakBlocks == null ? List.of() : allowedBreakBlocks), Set.of());
    }

    public boolean allowsBreak(String blockId) {
        return maxBreakBlocks > 0 && blockId != null && allowedBreakBlocks.contains(blockId);
    }

    public boolean allowsPlace(String blockId) {
        return maxPlaceBlocks > 0 && blockId != null && allowedPlaceBlocks.contains(blockId);
    }

    public GridPathPlanner.Budget budget() {
        return new GridPathPlanner.Budget(maxBreakBlocks, maxPlaceBlocks, maxRiskUnits, 256);
    }

    private static Set<String> normalized(Set<String> values, String field) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String blockId = value == null ? "" : value.trim();
                if (!BLOCK_ID.matcher(blockId).matches()) {
                    throw new IllegalArgumentException(field + " contains an invalid block id");
                }
                normalized.add(blockId);
            }
        }
        if (normalized.size() > MAX_BLOCK_TYPES) {
            throw new IllegalArgumentException(field + " exceeds the bounded type count");
        }
        return Set.copyOf(normalized);
    }
}
