# Dual-loader Hermes acceptance execution status

Updated: 2026-07-26 18:53 +08:00

This is the checkpoint log for
`MCAC_CODEX_DUAL_LOADER_HERMES_EXECUTION(1).md`. It records evidence without
promoting Replay, deterministic clients, GameTest, or local automation to
Live-Hermes or human-play evidence. `docs/RC_COMPLETION_MATRIX.md` remains the
only completion matrix.

## Current checkpoint

- Current stage: `2 — BEHAVIOR_SEMANTICS_AND_UX`
- Baseline source SHA: `63fbb2f66fbebebf9a68cf6b3e08304f0337e765`
- Latest committed source SHA: `1f56e6e011f6e6527fe3376427ecdbaf9a684f87`
- Source/default branch at freeze: `origin/main` / `main`
- Working branch: `codex/forge-hermes-human-acceptance`
- Final seal allowed: `NO`
- Live Hermes evidence: `NOT_RUN`
- Human playtest evidence: `NOT_RUN`
- Stage 0 result: `PASS`
- Stage 1 result: `PASS`

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
  accurately. This describes the frozen baseline artifact; the stage-1
  generator repair below does not rewrite that historical evidence.
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
- Repaired SBOM identity generation to read embedded Maven coordinates and
  versions or signed JAR Manifest versions. MCAC, Maven, and OpenJDK components
  now carry distinct versions, declared licenses, supplier, purl, available
  download source, and exact hashes.
- Made SBOM creation time deterministic from the source commit and strengthened
  package verification to require exact JAR-set coverage, source-SHA binding,
  real versions/licenses/purls, known dependency versions, and rejection of
  product-version overwrite. A rebuilt 27-package release passes the new gate.
- Replaced the process-lifetime reusable browser bootstrap URL with hash-only
  256-bit tickets bound to the server instance and owner PID, limited to 32,
  expiring after 30 seconds, and deleted on every consume attempt.
- Removed reusable bootstrap URLs from ordinary console/current-instance state.
  Double-click reuse now authenticates an internal loopback request and receives
  a fresh one-use ticket; optional test state contains only a short-lived
  one-use URL and no session, CSRF, or reopen secret.
- Added binding, expiry, capacity, replay, cross-site, internal-reopen, no-store,
  and state-redaction tests while preserving the existing second-launch flow.
- Replaced all Web `latest` declarations with the exact versions already
  resolved by the lockfile, moved Vite/build plugins out of production
  dependencies, and pinned Fabric Loom from `1.17-SNAPSHOT` to `1.17.17`.
- Pinned every GitHub Action invocation to an official resolved 40-character
  commit and added a local check that rejects mutable npm, Gradle, or Action
  declarations.
- Added weekly Dependabot coverage for all Gradle roots, npm and Actions, plus a
  least-privilege dependency-review/Java+TypeScript CodeQL workflow. It is
  configured but remains remotely unverified on this branch.
- Repaired one high-severity transitive development dependency reported by the
  real npm audit; a clean `npm ci`, full audit (`0 vulnerabilities`), all Web
  tests, Web build, and fixed Loom build pass.
- Extended the release SBOM and verifier from 27 JAR entries to 31 components:
  the exact four production npm bundle components now carry real versions,
  licenses, purls, source tarballs, and lockfile SHA-512 checksums.
- Replaced single-side-effect generic interaction checks with bounded
  multi-postcondition verification. Block/entity interactions now accept
  observable world/entity/inventory/vehicle/menu changes; menu actions also
  observe synchronized menu data and state ID in addition to slot/carried
  changes.
- Made exact placed-world state authoritative, so verified Creative and
  reusable-item placement no longer fails merely because inventory did not
  decrease.
- Unobserved generic effects now stop with exact `UNCERTAIN_EFFECT` propagation
  to the external Brain. The stopped non-idempotent progress is discarded, so
  explicit resume requires recovery and cannot repeat the uncertain action.
- Expanded Fabric from 23 baseline GameTests to 26. The complete 26/26 suite
  passes, including real-server Creative placement, a synthetic Mod-style
  synchronized-data-only menu, and uncertain-effect/no-replay cases. This is
  GameTest evidence, not Live Hermes, third-party-Mod, or human-play evidence.
- After the slice, the complete repository `check` and all three Loader builds
  pass; Web remains nine files/thirteen tests and dependency, documentation,
  forbidden-API, independence, and secret gates remain green.
- Closed stage 1 on committed source `fa17df780f2721af8a60fde8408608371aae318b`.
  The top-level three-Loader `gameTest` gate passed (Fabric 26/26, Forge 1/1,
  NeoForge 1/1), and `runtimeFabricE2E` passed its authenticated deterministic
  external-client, ASK_USER, restart, crash-import/no-replay, generic Registry,
  menu and transfer chains. This remains local deterministic/Replay evidence.
- Began stage 2 with the external-Brain semantic-state slice. Hermes may author
  a strict version-1 snapshot covering every section 5.1 field; Runtime only
  validates, session-scopes, versions, persists, restores, and presents it.
  The descriptive permission preset cannot alter Tool Gateway authority.
- Added migration 24, same-turn projection, authenticated audit visibility,
  and a Brain-page readout. Adapter, malformed-state, persistence/revision,
  cross-scope rejection, focused Runtime, Web test, and production Web build
  evidence pass locally. This is not Live Hermes or human evidence.
- Added migration 25 for local per-Companion initiative/personality settings,
  defaulting to `NORMAL`/`COMPANION`. Runtime injects the constraint into
  bounded Brain context and rejects a conflicting Brain-authored snapshot.
  Authenticated management and HTML controls can change both modes without
  changing Tool definitions, permissions, safety, budgets, or Memory policy.
  Proactive-message admission/rate/dedupe remains unfinished.
- Added the section 5.4 structured completion-claim boundary. Hermes final
  responses must declare verified, unverified, or non-task status. Verified
  claims cite the exact same-session terminal observation chosen by Hermes;
  Runtime validates and migration 26 links it to audit without inventing an
  acceptance script. Honest unverified claims require an explanation and no
  observation citation. Audit and HTML UI expose the relationship.
- Added migration 27 and authenticated local Memory management: view,
  search/filter, edit, delete, per-category clear, automatic body-observation
  save/pause, source/update/scope display, retained prior versions, and an
  aggregate-only safe export. Central storage rejects common credentials and
  explicit chat/Prompt/Search-body sources; support bundles still exclude the
  Runtime database and a sentinel test proves Memory body content is absent.
- Kept Episode Capsules, quarantined candidates, and formal Memory visually and
  durably separate. Cross-Companion automatic-save isolation, edit/delete
  history, sensitive-value rejection, Runtime HTTP actions, HTML dispatch, Web
  production build, and the complete repository check pass.
- Fixed two Task Graph worker handoff races found by the full regression run:
  awaiting an already persisted pause no longer times out and cancels it while
  admission cleanup finishes, and a durable ASK_USER question is not exposed
  until the prior worker has released admission. The complete Task Graph class
  and root `check` pass after the repair.

## Remaining work

- Complete stage-2 instruction/interruption semantics, proactive initiative
  admission/rate/deduplication, automatic saving of explicitly stated stable
  low-risk preferences, and one-time Skill trial leases without moving
  high-level planning authority into MCAC.
- Complete stages 2–6 before requesting any Live-Hermes or human action.
- Discover the real Hermes entry point and credentials safely at stage 7.
- Run real dual-loader Hermes and human acceptance before any final seal.

## User-action blockers

None at this checkpoint.
