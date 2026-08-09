package com.mccompanion.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mccompanion.minecraft.bridge.RuntimeCommandArguments;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeCommandArgumentsTest {
  @Test
  void selectsNestedSkillParametersFromTheRuntimeWireShape() {
    Map<String, Object> parameters = Map.of(
        "itemId", "minecraft:diamond",
        "count", 3,
        "menuToken", "menu-token");
    Map<String, Object> arguments = Map.of(
        "behaviorId", "behavior-1",
        "skill", "inventory.transfer",
        "itemId", "wrong-top-level-value",
        "parameters", parameters);

    assertEquals(parameters, RuntimeCommandArguments.skillParameters(arguments::get));
  }
}
