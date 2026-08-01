package com.mccompanion.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mccompanion.terminal.runtime.RuntimeProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmokeTestServiceTest {
  @TempDir Path temp;

  @Test
  void latestResultSurvivesServiceRestartAndIsStoredWithoutSecrets() throws Exception {
    RuntimeProfile profile = new RuntimeProfile("instance-one", temp, temp.resolve("runtime.cmd"), 8766);
    Instant now = Instant.now();
    SmokeTestService first = new SmokeTestService();
    String privateMarker = "unit-private-marker";
    first.record(
        profile,
        new SmokeTestService.Result(true, false,
            "passed Authorization: " + "Bear" + "er " + privateMarker),
        Clock.fixed(now, ZoneOffset.UTC));

    SmokeTestService.StoredResult restored = new SmokeTestService().latest(profile);
    assertEquals("SUCCEEDED", restored.state());
    assertTrue(restored.success());
    assertEquals(now, restored.completedAt());
    assertFalse(restored.summary().contains(privateMarker));
    String disk = Files.readString(temp.resolve("smoke-result.json"));
    assertFalse(disk.contains(privateMarker));
  }
}
