package com.mccompanion.core.body;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Single source of truth for body-control ownership.
 *
 * <p>Priority is intentionally fixed: safety reflex, owner-immediate control, Runtime task, idle.
 * A safety claim is latched until the body layer observes that the hazard cleared. Every accepted
 * claim receives a fencing token; releasing authority without that exact token is rejected.
 */
public final class BodyControlArbiter {
  public enum Authority {
    IDLE,
    RUNTIME_TASK,
    OWNER_IMMEDIATE,
    SAFETY_REFLEX
  }

  public record Decision(
      boolean accepted,
      String code,
      Authority previous,
      Authority current,
      long revision,
      String reason,
      String claimToken) {
    public Decision(boolean accepted, String code, Authority previous, Authority current,
                    long revision, String reason) {
      this(accepted, code, previous, current, revision, reason, "");
    }
  }

  public record Snapshot(Authority authority, long revision, String reason,
                         String claimToken, String ownerIdentity) {
    public Snapshot(Authority authority, long revision, String reason) {
      this(authority, revision, reason, "", "");
    }
  }

  private final Map<UUID, Snapshot> states = new HashMap<>();

  public synchronized Decision claim(UUID companionId, Authority requested, String reason) {
    return claim(companionId, requested, reason, reason);
  }

  /** Claims an authority for an identity such as lease + epoch + behavior ID. */
  public synchronized Decision claim(UUID companionId, Authority requested,
                                     String ownerIdentity, String reason) {
    Objects.requireNonNull(companionId, "companionId");
    Objects.requireNonNull(requested, "requested");
    if (requested == Authority.IDLE) throw new IllegalArgumentException("Use release for IDLE");
    String normalizedReason = normalize(reason);
    String normalizedOwner = normalize(ownerIdentity);
    Snapshot before = snapshot(companionId);
    if (before.authority() == Authority.SAFETY_REFLEX
        && requested != Authority.SAFETY_REFLEX) {
      return rejected("SAFETY_REFLEX_ACTIVE", before, normalizedReason);
    }
    if (before.authority() == Authority.OWNER_IMMEDIATE
        && requested == Authority.RUNTIME_TASK) {
      return rejected("OWNER_CONTROL_ACTIVE", before, normalizedReason);
    }
    if (before.authority() == requested && before.reason().equals(normalizedReason)
        && before.ownerIdentity().equals(normalizedOwner)) {
      return new Decision(true, "UNCHANGED", before.authority(), before.authority(),
          before.revision(), before.reason(), before.claimToken());
    }
    Snapshot after = new Snapshot(requested, before.revision() + 1, normalizedReason,
        UUID.randomUUID().toString(), normalizedOwner);
    states.put(companionId, after);
    return accepted(before, after);
  }

  /**
   * Legacy release shape intentionally cannot release a live claim. Callers must present the
   * token returned by the matching claim, so delayed callbacks cannot release a newer owner.
   */
  public synchronized Decision release(UUID companionId, Authority expected, String reason) {
    return release(companionId, expected, "", reason);
  }

  public synchronized Decision release(UUID companionId, Authority expected,
                                       String claimToken, String reason) {
    Objects.requireNonNull(companionId, "companionId");
    Objects.requireNonNull(expected, "expected");
    Snapshot before = snapshot(companionId);
    String normalizedReason = normalize(reason);
    if (before.authority() != expected) {
      return rejected("CONTROL_OWNER_MISMATCH", before, normalizedReason);
    }
    if (claimToken == null || claimToken.isBlank()) {
      return rejected("CONTROL_CLAIM_REQUIRED", before, normalizedReason);
    }
    if (!before.claimToken().equals(claimToken)) {
      return rejected("STALE_CONTROL_CLAIM", before, normalizedReason);
    }
    Snapshot after = new Snapshot(Authority.IDLE, before.revision() + 1, normalizedReason, "", "");
    states.put(companionId, after);
    return accepted(before, after);
  }

  public synchronized Decision releaseCurrent(UUID companionId, String reason) {
    Snapshot before = snapshot(companionId);
    if (before.authority() == Authority.IDLE) {
      return new Decision(true, "UNCHANGED", Authority.IDLE, Authority.IDLE,
          before.revision(), before.reason(), "");
    }
    return release(companionId, before.authority(), "", reason);
  }

  public synchronized Decision releaseCurrent(UUID companionId, String claimToken, String reason) {
    Snapshot before = snapshot(companionId);
    if (before.authority() == Authority.IDLE) {
      return new Decision(true, "UNCHANGED", Authority.IDLE, Authority.IDLE,
          before.revision(), before.reason(), "");
    }
    return release(companionId, before.authority(), claimToken, reason);
  }

  public synchronized Snapshot snapshot(UUID companionId) {
    Objects.requireNonNull(companionId, "companionId");
    return states.getOrDefault(companionId, new Snapshot(Authority.IDLE, 0, "initial", "", ""));
  }

  public synchronized void clear(UUID companionId) {
    states.remove(Objects.requireNonNull(companionId, "companionId"));
  }

  public synchronized int trackedCompanions() {
    return states.size();
  }

  private static Decision accepted(Snapshot before, Snapshot after) {
    return new Decision(true, "OK", before.authority(), after.authority(), after.revision(),
        after.reason(), after.claimToken());
  }

  private static Decision rejected(String code, Snapshot before, String reason) {
    return new Decision(false, code, before.authority(), before.authority(), before.revision(),
        reason, before.claimToken());
  }

  private static String normalize(String reason) {
    if (reason == null || reason.isBlank()) return "unspecified";
    String value = reason.replaceAll("[\\r\\n\\t]+", " ").strip();
    return value.length() <= 192 ? value : value.substring(0, 192);
  }
}
