# RC completion matrix

Updated: 2026-07-16  
Baseline: `d95598a`  
Overall status: `PARTIAL`

Status values are limited to `NOT_STARTED`, `PARTIAL`, `IMPLEMENTED`, `LOCALLY_VERIFIED`,
`REMOTELY_VERIFIED`, and `BLOCKED_BY_EXCLUDED_LIVE_TEST`. Replay evidence is not Live evidence.

| Module | Status | Implementation evidence | Test evidence | Remaining gap |
|---|---|---|---|---|
| External Brain Bridge | REMOTELY_VERIFIED | `runtime/.../brain`, authenticated `/brain`, game/Web ingress | PR fast, Windows, Minecraft heavy at `d95598a` | Live provider verification excluded |
| Async Tool Loop | LOCALLY_VERIFIED | durable session/call/task/behavior binding; immediate PAUSED/RECONCILIATION mapping; timeout cancellation confirmation; audited callId dedupe | PAUSED/reconciliation/timeout, duplicate-call, concurrent-cancel, companion-isolation and restart tests; prior real Fabric E2E | Remote verification of current SHA |
| Hermes Resume | LOCALLY_VERIFIED | same-session resume, durable question recovery, and once-only undelivered observation delivery | coordinator interruption/resume and audit restart tests | Remote disconnect/backoff E2E and protocol doctor |
| External ASK_USER | LOCALLY_VERIFIED | structured Hermes/OpenAI-compatible question; durable brainSession/task binding; Web/game routing; idempotent answer | adapter/restart/HTTP/game Replay plus real Fabric `6/16 -> ASK -> withdraw 6 -> return -> deliver` E2E | Remote verification of current SHA; Live/Human excluded |
| Search | PARTIAL | bounded query/open/citations/cancel gateway and privacy filtering | Runtime Replay tests, remote fast | user UI configuration, cache, doctor, full session lifecycle |
| Memory | PARTIAL | four types, provenance, user CRUD/export | repository and Web tests | working-memory cleanup, episodic automation, conflict/capacity policy |
| LocateKnownContainer | LOCALLY_VERIFIED | bounded `world.locate_known_container` returns only body/user-verified World Memory and flags cross-dimension candidates | multi-container, cross-dimension, unverified-filter Runtime tests | remote verification of current SHA |
| Container | LOCALLY_VERIFIED | vanilla withdraw/deposit, verified deltas, and known-container lookup | Fabric GameTests and Runtime/Fabric E2E | multi-container fill, invalidation/restart breadth |
| Craft | LOCALLY_VERIFIED | vanilla 2x2/3x3 recipe/menu execution | Fabric GameTests | broader recipe/restart cases |
| Explore | NOT_STARTED | — | — | `world.scan`, `ExploreArea`, budgets and ranking |
| Collect | NOT_STARTED | — | — | legal break/drop collection tool |
| Mine | NOT_STARTED | — | — | vein/tool/durability/drop verification |
| Smelt | NOT_STARTED | — | — | furnace lifecycle and multi-round verification |
| Retreat | NOT_STARTED | — | — | deterministic safety reflex and task interruption |
| Defend | NOT_STARTED | — | — | owner defense body capability |
| Multi-strategy | PARTIAL | External Brain can chain verified tools | container chain Replay E2E | route/resource/tool/fuel/diamond strategy E2E |
| MCP / Tool Protocol | NOT_STARTED | bounded in-process Tool Gateway only | Tool Gateway unit tests | local versioned MCP or equivalent public protocol |
| Web UI | PARTIAL | chat, Brain audit, memory and conversation views | Vitest and existing Playwright flow | provider/search/privacy/tool-progress completeness |
| Game UX | PARTIAL | natural reply outbox and legacy waiting question delivery | Runtime/Fabric conversation evidence | External Brain progress and ASK_USER answer flow |
| Reliability | PARTIAL | WAL, leases, restart interruption, dedupe | restart/persistence/E2E tests | long-run, reconnect/backoff, leak/performance breadth |
| Installer | PARTIAL | Windows installer components exist | installer unit tests | clean-directory full product smoke |
| Migration | PARTIAL | versioned SQLite migrations | migration rollback/idempotency tests | configuration migration and upgrade backup proof |
| Upgrade | PARTIAL | terminal upgrade components exist | limited unit coverage | atomic end-to-end upgrade verification |
| Rollback | PARTIAL | rollback components exist | limited unit coverage | failed-upgrade full rollback smoke |
| Uninstall | PARTIAL | uninstall support exists | limited unit coverage | process/port/data retention clean smoke |
| Doctor | PARTIAL | diagnostics modules and health endpoints | unit/CI coverage | Brain/Search protocol doctors |
| Support Bundle | PARTIAL | diagnostics/support infrastructure exists | CI coverage | privacy-redacted failure bundle full verification |
| Release Package | PARTIAL | platform artifacts build | `buildPlatforms` | complete RC package, SBOM, manifest and SHA alignment |
| CI | REMOTELY_VERIFIED | three GitHub Actions workflows | all three green at `d95598a` | must remain green for final SHA |

## Excluded final verification

- `LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING`
- `HUMAN_PLAYTEST_PENDING`

## Current execution order

1. Close remaining asynchronous Tool Loop state/cancellation/recovery edge cases.
2. Migrate External Brain `ASK_USER` to durable questions and resume the same Brain session.
3. Implement the generic Minecraft body capabilities and verified multi-strategy E2E.
4. Finish standard Tool protocol, product UI, reliability, installer, and release packaging.
