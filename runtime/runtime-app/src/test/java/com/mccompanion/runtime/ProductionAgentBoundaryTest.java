package com.mccompanion.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mccompanion.runtime.health.RuntimeHealthServer;
import com.mccompanion.runtime.websocket.RuntimeWebSocketServer;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ProductionAgentBoundaryTest {
  private static final Set<String> FORBIDDEN = Set.of(
      "com.mccompanion.runtime.agent.AgentKernel",
      "com.mccompanion.runtime.agent.AgentPlanRepository",
      "com.mccompanion.runtime.provider.ProviderRouter");

  @Test
  void productionEntryPointsCannotBindLegacyHighLevelAgentTypes() {
    for (Class<?> type : Set.of(RuntimeApplication.class, RuntimeHealthServer.class,
        RuntimeWebSocketServer.class, RuntimeCli.class)) {
      Stream<String> fieldTypes = Arrays.stream(type.getDeclaredFields()).map(Field::getType).map(Class::getName);
      Stream<String> executableTypes = Stream.concat(
          Arrays.stream(type.getDeclaredConstructors()), Arrays.stream(type.getDeclaredMethods()))
          .flatMap(ProductionAgentBoundaryTest::signatureTypes);
      Set<String> bound = Stream.concat(fieldTypes, executableTypes)
          .filter(FORBIDDEN::contains).collect(java.util.stream.Collectors.toSet());
      assertTrue(bound.isEmpty(), type.getName() + " binds legacy Agent types: " + bound);
    }
  }

  private static Stream<String> signatureTypes(Executable executable) {
    return Arrays.stream(executable.getParameterTypes()).map(Class::getName);
  }
}
