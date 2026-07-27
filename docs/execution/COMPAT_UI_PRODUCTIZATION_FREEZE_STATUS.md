# Compatibility, UI and productization freeze execution status

Updated: 2026-07-27 17:43 +08:00

This is the resumable checkpoint for
`MCAC_CODEX_产品化固定前主执行文件_v1.md`. It records automation evidence
without treating fixtures, Replay, GameTest, or browser automation as Live
Hermes or human-play evidence. `docs/RC_COMPLETION_MATRIX.md` remains the only
completion matrix.

## Repository checkpoint

- Base branch: `main`
- Base SHA: `63fbb2f66fbebebf9a68cf6b3e08304f0337e765`
- Working branch: `codex/forge-hermes-human-acceptance`
- PR: `#3` (`OPEN`, `DRAFT`, `MERGEABLE`)
- PR head: `b2ddfffa672b7443d2165e0f5928ec57a945ec16`
- Merge base: `63fbb2f66fbebebf9a68cf6b3e08304f0337e765`
- Working tree at audit start: `CLEAN`
- PR delta at audit start: 80 commits, 151 files

## Current phase

- Phase: `1 — SAFETY_AND_RELIABILITY_RESCAN`
- Last successful commands:
  - `git fetch --all --prune`
  - PR/base/head/merge-base audit
  - execution-contract and compatibility-spec exact-content verification
  - `clean check buildPlatforms`
  - `launchTest`
  - `gameTest` (Fabric 31, Forge 4, NeoForge 1)
  - `runtimeFabricE2E runtimeForgeE2E`
  - `persistenceRestartTest forgePersistenceRestartTest`
  - `runtimeMultiProfileTest runtimeDisabledLaunchTest`
  - `verifyTerminalPackage htmlTerminalStartTest releaseGoldenPathTest`
  - Web `npm ci`, audit, lint, unit tests, build and Playwright E2E
- Baseline defect fixed: the Fabric mining fixture now contains randomized
  vanilla drops on a bounded landing platform and timeout failures include
  behavior, position and evidence diagnostics. Assertions and timeout remain
  unchanged.
- Last failed command: initial combined `launchTest gameTest`; Fabric mining
  pickup timed out before the bounded fixture correction.
- Next step: complete the repository-wide safety/reliability rescan and its
  evidence, then implement the Compatibility Host vertical slice.

## Current support matrix

| Target | Current mode |
|---|---|
| Fabric 1.21.1 | `FULL_RUNTIME_BRIDGE` |
| Forge 1.20.1 | `FULL_RUNTIME_BRIDGE` |
| NeoForge 1.21.1 | `LOCAL_ONLY` |

## Current release evidence

- Product version: `0.3.0`
- Last fully verified executable checkpoint:
  `7fbc47d7595f3fbab6ddbea2ed35bbaa56669e5f`
- Release ZIP SHA-256:
  `acd85f451f039268deb4c74b9a107ce1d1369cd8e0e89a5a51c97d24f78bf4b2`
- This is recovery evidence only. A new package must be generated from the
  final candidate and again from the merged `main` SHA.

## Remote evidence at recovery point

| Gate | Run | Result |
|---|---:|---|
| PR fast | `30251548244` | `SUCCESS` |
| Windows terminal/package | `30251547954` | `SUCCESS` |
| Supply chain and CodeQL | `30251547976` | `SUCCESS` |
| Minecraft heavy at executable parent `7fbc47d` | `30250341444` | `SUCCESS` |

## Execution boundaries

- Compatibility Host remains deterministic infrastructure, not a high-level
  planner.
- External development Agents may use the bounded compatibility management
  contract; runtime game Brains may not compile code, edit active indexes, or
  gain shell/arbitrary-file/credential authority.
- Live Hermes and human play are deliberately post-freeze work and are not
  merge gates for this execution.
- Merge allowed: `NO`
- Productization baseline created: `NO`
- User-action blocker: `NONE`
