# Dual-loader Hermes acceptance execution status

Updated: 2026-07-26 12:14 +08:00

This is the checkpoint log for
`MCAC_CODEX_DUAL_LOADER_HERMES_EXECUTION(1).md`. It records evidence without
promoting Replay, deterministic clients, GameTest, or local automation to
Live-Hermes or human-play evidence. `docs/RC_COMPLETION_MATRIX.md` remains the
only completion matrix.

## Current checkpoint

- Current stage: `1 — AUDIT_HARDENING`
- Baseline source SHA: `63fbb2f66fbebebf9a68cf6b3e08304f0337e765`
- Latest working source SHA: `63fbb2f66fbebebf9a68cf6b3e08304f0337e765`
- Source/default branch at freeze: `origin/main` / `main`
- Working branch: `codex/forge-hermes-human-acceptance`
- Final seal allowed: `NO`
- Live Hermes evidence: `NOT_RUN`
- Human playtest evidence: `NOT_RUN`
- Stage 0 result: `PASS`

## Frozen environment

| Component | Baseline value |
|---|---|
| Host | Windows 11 10.0 amd64 |
| Java on PATH | Oracle JDK 25, `25+37-LTS-3491` |
| Gradle | 8.14.5 |
| Gradle launcher/daemon JVM | Oracle JDK 25 |
| Node.js | 22.22.3 |
| npm | 10.9.8 |
| Fabric target toolchain | Java 21 |
| Forge 1.20.1 target toolchain | Java 17 |
| NeoForge target toolchain | Java 21 |

## Baseline support truth

- Fabric 1.21.1: current audited RC full Runtime path; Live Brain and human
  verification remain pending.
- Forge 1.20.1: builds, loads, launches, and has one lifecycle GameTest, but its
  current capability report is local companion control only. It is not a Full
  Runtime Bridge at this checkpoint.
- NeoForge 1.21.1: `LOCAL_ONLY`; it is outside the new Full Bridge target.
- The current release staging includes artifacts for all three targets. Loader
  installation selection and final public support wording require later
  dual-loader verification.

## Baseline remote evidence

All three latest `main` workflows completed successfully on the frozen SHA:

| Workflow | Run ID | Result |
|---|---:|---|
| PR fast checks | 30184815597 | `success` |
| Windows terminal validation | 30184815562 | `success` |
| Minecraft heavy validation | 30184815565 | `success` |

## Baseline release evidence

- Staged manifest source: `63fbb2f66fbebebf9a68cf6b3e08304f0337e765`
- Product version: `0.3.0`
- Manifest payload entries: 401
- Staged SBOM package entries: 27
- Baseline release ZIP SHA-256:
  `79480d0a386bffbdf3ba30bc43b4e6b7eac3202939a0c9354276542cc9cacb42`
- Baseline release manifest SHA-256:
  `ec19745317c5b3d7b773ca5f4047e5da951dcfaa181a10845e3579e98b1bd991`
- Baseline SBOM SHA-256:
  `e2626b2724669f8021df47fa68d42a8255d6a3bbc034b2307e9da4b5586336b4`
- Confirmed audit defect: all 27 staged JAR packages currently use product
  version `0.3.0`; dependency versions and licenses are not represented
  accurately. This is a stage-1 SBOM repair item, not a PASS.
- A fresh release ZIP will be created only after the remaining baseline package
  tasks; no stale pre-clean ZIP is counted as this branch's baseline artifact.

## Automated baseline evidence

Passed on the frozen SHA:

- `clean check`: shared JVM tests, nine Web test files / thirteen tests,
  forbidden API, independence, secret, and documentation checks.
- `buildPlatforms`: Fabric 1.21.1, Forge 1.20.1, and NeoForge 1.21.1.
- `launchTest`: real dedicated-server startup on all three loaders.
- `gameTest`: Fabric 23 required tests, Forge one required lifecycle test, and
  NeoForge one required lifecycle test.
- `runtimeFabricE2E`: authenticated Runtime/Fabric external-client chain,
  Registry/recipe/menu primitives, ASK_USER, restart, and non-idempotent crash
  reconciliation. This run used deterministic/Replay providers and is not Live
  Hermes evidence.
- `persistenceRestartTest`, `runtimeDisabledLaunchTest`, and
  `runtimeMultiProfileTest`.
- focused reconnect, capsule, memory-candidate, 105-turn long-play, 200-turn
  soak, and generic unknown-Mod suites;
- release package verification, TUI/root/release entry points, authenticated
  HTML first-start and single-instance reuse, and the clean-extraction browser
  release golden path.

## Current failures and audit observations

- No baseline command has failed.
- Top-level Gradle has emitted `DisconnectableInputStream ... Write end dead`
  after otherwise successful nested-loader invocations. It is non-fatal in the
  observed runs but is retained for shutdown/thread audit.
- JDK 25 emits a future native-access warning for SQLite JDBC. Target release
  toolchains remain Java 21/17; the warning is retained for compatibility
  review.
- Forge and NeoForge GameTest coverage is only one lifecycle case each, far
  below Fabric's primitive/Registry/menu/effect coverage. Forge parity remains
  unimplemented.
- Live Hermes and human play evidence are intentionally absent.

## Completed work

- Read and froze the complete 1,268-line execution contract.
- Fetched all remotes and confirmed local `main` equals `origin/main`.
- Created the dedicated execution branch.
- Recorded environment, support, CI, manifest, SBOM, and initial baseline
  evidence.
- Completed the repository-declared stage-0 baseline without skipped failures
  and generated a fresh verified baseline package.

## Remaining work

- Execute stage-1 audits and repairs without weakening current guarantees.
- Complete stages 2–6 before requesting any Live-Hermes or human action.
- Discover the real Hermes entry point and credentials safely at stage 7.
- Run real dual-loader Hermes and human acceptance before any final seal.

## User-action blockers

None at this checkpoint.
