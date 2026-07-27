# Safety and reliability rescan — 2026-07-27

Scope: production Java, Gradle/PowerShell automation, Web source and current
tests on `codex/forge-hermes-human-acceptance`. Generated outputs,
`node_modules`, archived execution records and fixtures were excluded from
source-pattern findings. This is an audit record, not a second completion
matrix.

## Closed findings

| Area | Finding | Resolution | Verification |
|---|---|---|---|
| Fabric GameTest | Vanilla ore drops could leave a one-block-wide fixture floor and make real pickup nondeterministic. | Added a bounded landing platform; retained the exact timeout and world, inventory, durability and evidence assertions. Timeout diagnostics now include behavior, position and evidence. | Two corrected Fabric runs passed; the complete Fabric 31 / Forge 4 / NeoForge 1 `gameTest` gate passed. |
| Installer and Profile allocation | Static path-keyed JVM lock maps retained every previously observed path for the process lifetime. | Replaced them with fixed-size lock stripes while retaining authoritative per-target OS file locks. | Installer, Runtime supervisor and Terminal suites passed, including existing same-target serialization and different-target concurrency cases. |
| Runtime planning | The legacy Agent replan executor and connected-body player planning executor used unbounded work queues. | Added finite 64-entry fair queues with abort backpressure. Saturated replans stay durably blocked; player requests receive `RUNTIME_BUSY`; optional owner-activity handoff stops safely. | New exact saturation tests and the complete Runtime suite passed. |
| HTML terminal window state | Authenticated window identifiers had no capacity or expiry. | Added a 64-window cap, 24-hour expiry and explicit `WINDOW_CAPACITY_REACHED`. | Terminal integration suite passed. |

## Reviewed controls

- Task Graph admission and worker queues remain bounded; non-idempotent unknown
  effects remain `RECONCILIATION_REQUIRED`.
- Terminal operations retain plan TTL, operation TTL, global capacities,
  bounded subscriber queues and same-instance serialization.
- HTTP JSON readers retain declared and observed size limits, compression
  rejection and bounded slow-read cleanup.
- Search transport retains pinned address validation, disabled redirects,
  bounded response sizes and credential isolation.
- Bootstrap tickets remain hash-only, one-use, short-lived, binding-scoped and
  capacity-limited.
- Support bundles remain allow-listed, excerpt-bounded, redacted and scanned
  after construction.
- Dependency pinning, lockfile/SBOM identity and CI Action pinning remain part
  of root `check`.

## Evidence boundary

Replay, fixtures, unit tests, browser automation and GameTest are labeled as
such. They do not constitute Live Hermes or human-play evidence. Those remain
`LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING` and `HUMAN_PLAYTEST_PENDING`.
