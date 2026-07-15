# External Brain productization state

Updated: 2026-07-15

## Current status

The repository is migrating to the External-Brain-first architecture defined by
`CODEX_EXECUTION.md`. This document tracks implementation evidence without using or
updating the Codex Goal UI.

Current milestone: `EXTERNAL_BRAIN_BRIDGE_REPLAY_VERIFIED`

The release is not yet `READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST` because Search Gateway,
the complete generic Minecraft tool set, External Brain persistence/reconnect,
product UI controls, and release/install verification remain incomplete.

## Completed in this slice

- Added one `ExternalBrainAdapter` contract and per-companion Brain sessions.
- Added `HermesBrainAdapter` using the bounded `mcac-brain/1` HTTP bridge.
- Added native OpenAI-compatible tool-calling for external model providers.
- Added an explicitly non-Live `ReplayBrainAdapter` for deterministic automation.
- Enforced one active external Brain controller and a bounded tool-call loop.
- Added a capability-filtered Tool Gateway with no shell, file, credential, cookie,
  arbitrary network, world-edit, or inventory-edit access.
- Added authenticated Runtime `/brain` ingress and persisted final conversation replies.
- Verified Runtime -> Brain -> `world.observe` -> observation -> Brain -> final response
  with a real Runtime process and a Replay Brain, without creating an internal plan or task.
- Kept credentials environment-variable-only. No real provider credential was used.

## Verification boundary

- Replay results are automation evidence only and must never be reported as Live.
- Adapter health is `CONFIGURED` until a real external service has been checked.
- The legacy internal `/agent` path remains temporarily available during migration;
  no new high-level planning behavior may be added to it.

## Pending external validation

- `LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING`
- `HUMAN_PLAYTEST_PENDING`

