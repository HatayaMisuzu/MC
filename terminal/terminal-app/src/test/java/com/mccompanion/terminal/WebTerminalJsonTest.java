package com.mccompanion.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

final class WebTerminalJsonTest {
  @Test
  void serializesJavaTimeAsIso8601TextForCompatibilityResponses() {
    var value = WebTerminalApi.JSON.valueToTree(Instant.parse("2026-07-27T00:00:00Z"));

    assertTrue(value.isTextual());
    assertEquals("2026-07-27T00:00:00Z", value.asText());
  }
}
