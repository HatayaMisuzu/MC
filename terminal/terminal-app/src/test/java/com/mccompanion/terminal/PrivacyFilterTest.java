package com.mccompanion.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrivacyFilterTest {
  private final PrivacyFilter filter = new PrivacyFilter();

  @Test
  void uiUsesStablePseudonymsAndRemovesSecretsAndChat() {
    String source = "token=secret 127.0.0.1 user@example.com 123e4567-e89b-42d3-a456-426614174000";
    String first = filter.filter(source, PrivacyFilter.Policy.UI_DEFAULT);
    String second = filter.filter(source, PrivacyFilter.Policy.UI_DEFAULT);
    assertEquals(first, second);
    assertFalse(first.contains("secret"));
    assertFalse(first.contains("127.0.0.1"));
    assertFalse(first.contains("user@example.com"));
    assertTrue(first.contains("<IP:"));
    assertEquals("<PRIVATE_CHAT_OMITTED>", filter.filterLogLine("[CHAT] <Alice> hello",
        PrivacyFilter.Policy.UI_DEFAULT));
  }

  @Test
  void shareableOutputLeavesNoPrivatePatterns() {
    String output = filter.filter("C:\\Users\\Alice\\game 10.0.0.2 host.example.com",
        PrivacyFilter.Policy.SHAREABLE_BUNDLE);
    assertFalse(filter.containsShareablePrivateData(output));
  }
}
