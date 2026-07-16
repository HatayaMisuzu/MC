# AGENTS.md — MCAC repository execution rules

This file applies to the entire repository.

## Product boundary

MCAC is not an internal high-level Agent.

```text
Player / game chat / HTML terminal
        ↓
MCAC Runtime
context · conversation · memory · search · primitive tools
task-graph execution · skill workspace · safety · persistence · verification
        ↓
Hermes / DeepSeek / another external LLM or Agent
```

Hermes, DeepSeek, or another configured external Brain is the only high-level decision-maker.
MCAC may validate and deterministically execute a Brain-authored graph, but it must not invent
open-ended goals, strategy, personality, or a competing plan.

```text
Task Graph Runtime = deterministic orchestration
External Brain = reasoning and planning
```

## Implementation rules

- Build generic primitive Tools, declarative Skills, and composable Task Graphs.
- Wood, diamonds, smelting, storage, defense, shelter building, and Mod interactions are
  representative acceptance cases, not Java Handler names or product boundaries.
- Do not add scenario classes such as `GetDiamondTask`, `BuildHouseHandler`, or equivalents.
- Existing composite capabilities may remain only as convenience wrappers over shared primitive
  executors or as built-in Skills. They must not remain the only behavior entry point.
- Unknown Mod content must use connected-body Registry data, generic interaction, and verified
  Observation. Do not add one adapter or Handler per Mod for ordinary Registry content.
- Emergency safety reflexes may preempt execution. They share executors and observations with
  explicit safety Tools and never become an internal planning Agent.
- Reuse the existing HTML terminal, installer, update, rollback, uninstall, Doctor, support-bundle,
  and release-packaging foundations.

## Runtime authority

The runtime external Brain may use only the bounded Tool Gateway, logical Agent Workspace, and
declarative JSON/YAML Task Graph/Skill resources. It must never receive:

- arbitrary shell, process, Git, Gradle, compiler, or network execution;
- arbitrary filesystem paths or production-source access;
- launcher credentials, browser cookies, secrets, or world-file mutation;
- direct inventory/world edits, teleportation, item generation, or fabricated success.

Development-time Codex may edit this repository. Runtime Hermes may not.

## Evidence and status

- Replay, Fake, Mock, unit tests, GameTest, and local automation must be labeled precisely.
- Replay is never Live-provider evidence. GameTest is real Minecraft execution but not human play.
- Only `docs/RC_COMPLETION_MATRIX.md` is the current completion matrix.
- Do not remove unfinished rows or lower acceptance standards to make the matrix appear complete.
- Update the matrix after each stable vertical slice.
- Run tests proportional to risk, then commit, push, and inspect remote CI.

The only permitted final readiness labels are:

```text
READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST_RC
LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING
HUMAN_PLAYTEST_PENDING
```

`GENERAL_PURPOSE_SCRIPT_VM_DEFERRED` may be listed as a known deferral only after the typed,
declarative Task Graph DSL covers bounded composition, conditions, loops, retries, recovery, and
Skill reuse.

## Current work order

1. Keep documentation aligned with code and the RC matrix.
2. Complete primitive Tool coverage.
3. Complete Task Graph execution, persistence, reconciliation, and restart recovery.
4. Implement isolated Agent Skill Workspace, quarantine, promotion, versioning, and rollback.
5. Harden MCP identity, permissions, replay protection, cancellation, and reconnect recovery.
6. Complete Registry/Mod compatibility, memory/search contamination protection, UI/game UX,
   general-purpose E2E, fuzz/failure injection, long-run tests, and current-version release checks.

Do not use or update Codex Goal UI for this execution stream.
