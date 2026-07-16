# MCAC current execution contract

Updated: 2026-07-16

This is the active Codex execution contract. It does not use Goal mode. Current implementation
status lives only in [docs/RC_COMPLETION_MATRIX.md](docs/RC_COMPLETION_MATRIX.md).

## Architecture

MCAC provides the Minecraft body, bounded primitive Tool Gateway, context, conversation, memory,
search, safety, persistence, verification, deterministic Task Graph Runtime, Skill Workspace, and
product surfaces. Hermes, DeepSeek, or another external LLM/Agent is the only high-level Brain.

```text
Task Graph Runtime = deterministic orchestration
External Brain = reasoning and planning
```

MCAC must not become a hidden Planner. It may validate, execute, pause, resume, retry, checkpoint,
reconcile, audit, and verify an external-Brain-authored graph. It may not choose an open-ended goal
or high-level strategy.

## Generic capability direction

Representative tasks such as obtaining wood or diamonds, smelting, organizing chests, defending
the owner, building shelter, or using unknown Mod content are black-box acceptance cases.
Production behavior must be expressed with:

```text
bounded primitive Minecraft Tools
+ reusable declarative Skills
+ typed Task Graphs authored/revised by the external Brain
```

Do not create task-specific Java Handlers. Existing composite capabilities remain temporarily for
compatibility and real-world evidence, but must migrate to shared primitive executors or built-in
Skills and cease to be the only entry point.

## Runtime permissions

Runtime Hermes may:

- inspect bounded Tool schemas and connected Registry information;
- read built-in or approved Skills;
- create and revise its own declarative Task Graphs and Skill drafts;
- validate/execute graphs through MCAC;
- inspect verified observations and bounded evidence;
- manage logical resources inside its isolated Workspace.

Runtime Hermes may not:

- execute Shell, PowerShell, CMD, Bash, Git, Gradle, compilers, or arbitrary processes;
- access arbitrary files, production Java, Minecraft saves, credentials, cookies, or secrets;
- access the network except through an explicitly authorized bounded Tool such as Search;
- replace or rebuild Runtime;
- bypass the Tool Gateway or directly edit world/inventory state.

## Required implementation streams

1. Primitive Tools: world/Registry/recipe/capability, movement, block, inventory/item, entity,
   menu, safety, and deterministic task controls.
2. Typed Task Graph: bounded JSON/YAML schema, safe expressions, validation, deterministic
   execution, checkpoints, persistence, reconciliation, and restart recovery.
3. Agent Skill Workspace: logical paths, quotas, extension allowlist, traversal/symlink/junction/
   ADS protection, companion isolation, generated-skill quarantine, validation, promotion,
   versioning, rollback, and revocation.
4. MCP: pairing, controller/Brain-session/companion binding, lease/expiry, nonce/call identity,
   permission checks, replay protection, same-session cancellation, SSE reconnect/reconciliation.
5. Registry/Mod: connected-body dynamic Registry data and generic interaction with honest
   observations for unknown namespaced content.
6. Memory/Search: provenance, TTL/revalidation, companion/world isolation, prompt-injection and
   untrusted-content barriers.
7. Product: extend the existing HTML terminal and installer/update/rollback/uninstall/Doctor/
   support/release engineering; do not rebuild parallel replacements.
8. Verification: schema conformance, fuzz/property tests, failure injection, primitive-only
   representative E2E, real Fabric evidence, restart, long-run/performance, clean install/update/
   rollback/uninstall, SBOM, manifest, SHA alignment, and remote CI.

## Evidence rules

- `LOCAL_PASS` is not `REMOTE_PASS`.
- Replay/Fake/Mock is not Live-provider evidence.
- Fabric GameTest proves real Minecraft code paths, not human play.
- Historical release reports do not establish current SHA readiness.
- Never mark a row complete solely because its interface/schema exists.

## Execution loop

```text
audit current code and matrix
→ implement one vertical capability
→ unit/integration/Fabric/Replay/UI/security/performance checks as applicable
→ fix
→ update RC matrix
→ commit
→ push
→ inspect remote CI
→ continue the highest-value unfinished item
```

The only final readiness labels are:

```text
READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST_RC
LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING
HUMAN_PLAYTEST_PENDING
```
