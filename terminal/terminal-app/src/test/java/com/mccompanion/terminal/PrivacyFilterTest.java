package com.mccompanion.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.terminal.WebTerminalApi;
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

  @Test
  void structuredDefaultProjectionHidesKnownAndFutureIdentifierFields() throws Exception {
    JsonNode source = WebTerminalApi.JSON.readTree("""
        {"instanceId":"instance-secret-1","taskId":"task-secret-2",
         "controlEpoch":42,"futureField":{"rawReference":"companion-secret-3"},
         "futureText":"task-secret-4","state":"RUNNING"}
        """);
    JsonNode safe = filter.sanitizeJson(source, PrivacyFilter.Policy.UI_DEFAULT);
    String text = safe.toString();
    assertFalse(text.contains("instance-secret-1"));
    assertFalse(text.contains("task-secret-2"));
    assertFalse(text.contains("companion-secret-3"));
    assertFalse(text.contains("task-secret-4"));
    assertEquals("RUNNING", safe.path("state").asText());
    assertEquals("42", filter.sanitizeJson(WebTerminalApi.JSON.readTree("42"),
        PrivacyFilter.Policy.UI_DEFAULT).asText());
  }

  @Test
  void authenticatedControlProjectionCanKeepExactHandlesWhileShareableProjectionCannot() throws Exception {
    JsonNode source = WebTerminalApi.JSON.readTree(
        "{\"companionId\":\"companion-secret\",\"taskId\":\"task-secret\"}");
    assertEquals(source, filter.sanitizeJson(source, PrivacyFilter.Policy.INTERNAL_RAW));
    String shareable = filter.sanitizeJson(source, PrivacyFilter.Policy.SHAREABLE_BUNDLE).toString();
    assertFalse(shareable.contains("companion-secret"));
    assertFalse(shareable.contains("task-secret"));
  }
}
