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
- Replaced both unbounded Task Graph executor queues with fair bounded queues.
- Added explicit 64-per-Runtime/Profile, 16-per-Brain-session, and
  24-per-Companion admission. Overload returns `QUEUE_FULL` or
  `ADMISSION_REJECTED`; a rejected new execution is not persisted.
- Preserved timed-wait consistency under saturation by leaving it `WAITING` and
  retrying admission instead of fabricating failure or silently losing work.
- Added saturation and cross-Companion capacity tests; the complete Runtime test
  suite passes after the change.
- Replaced the two-thread shared management bottleneck with fair bounded
  management and planning pools. Synchronous MCP and streaming MCP have
  separate 8/4 concurrency limits that reserve control capacity for health and
  cancellation; saturation returns `RUNTIME_BUSY`.
- Added bounded-pool rejection/termination coverage and reran the Runtime HTTP
  integration, including health availability during a blocked Brain request.
- Added same-Profile Runtime start/stop exclusion with a fair JVM lock, an OS
  file lock, ten-second timeout, owner PID/operation metadata, and crash-safe
  reacquisition. Different Profiles remain independent.
- Bounded Terminal plans and operations (128/512), added a four-worker/64-entry
  operation queue, retained terminal results for 30 minutes, and serialized all
  managed operations for the same instance across Runtime/install categories.
- Added lock timeout/recovery/different-Profile, same-instance serialization,
  and plan/operation expiry tests.
- Added pre-parse Runtime request boundaries: 16 KiB for Brain, 128 KiB for
  ordinary management, and 1 MiB for MCP, with eight bounded readers and a
  five-second deadline. Compression, conflicting framing, bad lengths, and
  actual chunked oversize are rejected before JSON parsing.
- Applied the same boundary to every Terminal JSON route with 16-KiB control
  and 1-MiB API limits.
- Added Runtime declared/chunked oversize, compression, length mismatch, slow
  cleanup and post-attack health coverage, plus authenticated Terminal
  declared/chunked oversize and compression coverage.
- Replaced preflight-only Search DNS checks with a pinned HTTP/TLS transport:
  every socket connects only to the already validated address set while TLS
  still verifies SNI and the original hostname.
- Added complete IPv4/IPv6 non-public range rejection, mixed-answer rejection,
  per-hop redirect validation, redirect-loop/cap handling, response framing
  bounds, and fail-closed Provider redirect behavior so bearer credentials are
  never forwarded.
- Added unit, simulated DNS-rebinding, redirect, and real loopback-socket
  Provider integration coverage for the Search boundary.

## Remaining work

- Execute stage-1 audits and repairs without weakening current guarantees.
- Continue stage-1 SBOM, bootstrap-ticket, supply-chain, and generic-effect
  verification audits.
- Complete stages 2–6 before requesting any Live-Hermes or human action.
- Discover the real Hermes entry point and credentials safely at stage 7.
- Run real dual-loader Hermes and human acceptance before any final seal.

## User-action blockers

None at this checkpoint.
