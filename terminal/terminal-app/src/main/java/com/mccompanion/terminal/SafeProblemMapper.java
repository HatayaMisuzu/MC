package com.mccompanion.terminal;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Converts exceptions into stable public problems while retaining a local correlation trail. */
final class SafeProblemMapper {
  private static final PrivacyFilter PRIVACY = new PrivacyFilter();
  private static final Pattern DOMAIN_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

  private SafeProblemMapper() { }

  static Problem expected(int status, String code, Throwable failure) {
    String raw = failure == null ? null : failure.getMessage();
    String message = raw == null || raw.isBlank() ? code : PRIVACY.filter(raw, PrivacyFilter.Policy.UI_DEFAULT);
    return new Problem(status, code, message, null);
  }

  static Problem unexpected(Throwable failure) {
    String correlationId = UUID.randomUUID().toString();
    System.err.println("MCAC terminal failure [" + correlationId + "]: "
        + failure.getClass().getName() + ": " + failure.getMessage());
    failure.printStackTrace(System.err);
    return new Problem(500, "INTERNAL_ERROR",
        "Unexpected terminal failure. Reference: " + correlationId, correlationId);
  }

  static Problem operationFailure(Throwable failure) {
    if (failure instanceof IOException && failure.getMessage() != null) {
      String raw = failure.getMessage().strip();
      String code = raw.split(":", 2)[0];
      if (DOMAIN_CODE.matcher(code).matches()) return expected(409, code, failure);
    }
    return unexpected(failure);
  }

  record Problem(int status, String code, String message, String correlationId) { }
}
