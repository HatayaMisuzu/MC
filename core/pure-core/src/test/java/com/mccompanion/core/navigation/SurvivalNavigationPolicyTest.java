package com.mccompanion.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SurvivalNavigationPolicyTest {
    @Test
    void convertsExplicitAuthorityToPlannerBudgets() {
        SurvivalNavigationPolicy policy = SurvivalNavigationPolicy.breaking(
                3, 7, List.of("minecraft:dirt", "examplemod:soft_rock"));

        assertTrue(policy.allowsBreak("minecraft:dirt"));
        assertFalse(policy.allowsBreak("minecraft:diamond_ore"));
        assertEquals(new GridPathPlanner.Budget(3, 0, 7, 256), policy.budget());
    }

    @Test
    void rejectsUnboundedOrImplicitMutationAuthority() {
        assertThrows(IllegalArgumentException.class,
                () -> new SurvivalNavigationPolicy(1, 0, 8, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SurvivalNavigationPolicy.breaking(9, 8, List.of("minecraft:dirt")));
        assertThrows(IllegalArgumentException.class,
                () -> SurvivalNavigationPolicy.breaking(1, 8, List.of("not a block")));
    }
}
