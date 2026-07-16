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
| Search | PARTIAL | bounded query/open/citations/cancel gateway and privacy filtering; Terminal/Web configuration covers explicit enable/disable, HTTPS-or-loopback endpoint, environment-only token, 1..30s timeout, and non-overlapping allow/deny domain policy; pairing writes validated policy into `runtime.yml` | Runtime Replay tests; Search configuration validation and Runtime YAML propagation tests; production Web build | provider connection/Search Doctor, cache, source-click UI, full session lifecycle |
| Memory | PARTIAL | four types, provenance, user CRUD/export | repository and Web tests | working-memory cleanup, episodic automation, conflict/capacity policy |
| LocateKnownContainer | LOCALLY_VERIFIED | bounded `world.locate_known_container` returns only body/user-verified World Memory and flags cross-dimension candidates | multi-container, cross-dimension, unverified-filter Runtime tests | remote verification of current SHA |
| Container | LOCALLY_VERIFIED | vanilla withdraw/deposit, verified deltas, and known-container lookup | Fabric GameTests and Runtime/Fabric E2E | multi-container fill, invalidation/restart breadth |
| Craft | LOCALLY_VERIFIED | vanilla 2x2/3x3 recipe/menu execution | Fabric GameTests | broader recipe/restart cases |
| Explore | PARTIAL | `world.scan` dispatches incremental `ExploreArea`: radius 1..16, 256 blocks/tick, loaded chunks only, 64 candidates, distance ranking | Runtime schema test; real Fabric GameTest proves asynchronous scan and ranked ore candidates with a batch-unique fixture | route exploration, world-change/unreachable/restart breadth, Runtime/Fabric Brain E2E |
| Collect | PARTIAL | `resource.collect` uses walking input plus vanilla `ItemEntity.playerTouch`; validates inventory delta, missing/insufficient/full/no-progress states | Runtime schema test; isolated real Fabric GameTest uses a bounded support surface, collects a coal stack after movement, and proves entity removal + inventory delta; full cross-loader GameTest passes | scattered/moving drops, restart/unreachable breadth, wood/iron/diamond cases, Brain E2E |
| Mine | PARTIAL | `resource.mine_vein` dispatches bounded `MineResourceVein`; discovers a connected six-neighbor vein, accumulates vanilla hardness progress, breaks through `ServerPlayerGameMode`, and collects actual drops before accepting verified inventory deltas | Runtime schema test; isolated real Fabric GameTest mines two supported connected diamond ores, verifies both world changes, two vanilla drops, iron-pickaxe durability >=2, and audited action-path evidence; full cross-loader GameTest passes | navigation to distant veins, mixed/common resources, tool break/full inventory/world change/restart breadth, Brain E2E |
| Smelt | PARTIAL | `item.smelt` resolves a vanilla smelting recipe, requires an empty verified furnace in reach, transfers held input/fuel through `FurnaceMenu`, waits for the real cook lifecycle, retrieves the result through the menu, and verifies the output inventory delta | Runtime schema boundary test; real Fabric GameTest smelts two raw iron with coal, verifies input consumption, result pickup, cleared input/result slots, observation and action evidence | fuel-duration preflight, alternate furnace/recipe/fuel breadth, busy/full/fuel-loss GameTests, cancellation/restart and Brain E2E |
| Retreat | PARTIAL | Runtime-independent pre-task reflex detects a live hostile within 3 blocks, interrupts any active navigation/skill through the normal action gateway, walks directly away with player input, requires >=3-block displacement and >=6-block threat clearance, then leaves the interrupted work explicitly PAUSED | isolated real Fabric safety GameTest starts travel toward a no-AI zombie, proves task interruption, increased hostile distance, movement delta, terminal observation and successful action evidence; full 11-test suite passes | terrain-aware escape planning, fire/lava/drowning directional recovery, multiple/ranged threats, restart and Runtime/Fabric Brain E2E |
| Defend | PARTIAL | `combat.defend_owner` is exposed only through the Runtime/Fabric capability intersection; selects one live hostile within 8 blocks of the owner, approaches with player input, honors vanilla attack cooldown, attacks through `ServerPlayer.attack`, and completes only when the threat dies or remains outside the 10-block protection radius | Runtime zero-argument schema test; isolated real Fabric combat GameTest kills a one-health husk, earns vanilla Monster Hunter advancement, consumes sword durability, and verifies terminal observation/evidence; full 12-test suite passes | hostile intent/ownership policy, multiple/ranged threats, armor/weapon strategy, friendly-fire breadth, cancellation/restart and Brain E2E |
| Multi-strategy | PARTIAL | External Brain can chain verified tools | container chain Replay E2E | route/resource/tool/fuel/diamond strategy E2E |
| MCP / Tool Protocol | PARTIAL | authenticated `/mcp` Streamable HTTP JSON-RPC endpoint negotiates MCP `2025-03-26`/`2025-06-18`, enforces the negotiated version, exposes only context-filtered MCAC Tool Gateway definitions, binds calls/cancellation to controller + Brain session + companion, streams token-bound `notifications/progress`, cancels disconnected streams, and returns structured terminal observations; `docs/MCP_PROTOCOL.md` covers lifecycle and Hermes connection contract | Runtime HTTP integration verifies auth, negotiation/version rejection, required binding headers, bounded tool list, rejected missing companion, JSON/SSE terminal responses, stable call identity and cancellation notification; protocol unit test verifies progress envelope/token; Terminal Doctor live-probes initialize + bounded tools/list | SSE event resumability, live concurrent cancellation E2E, and verified Hermes-native configuration |
| Web UI | PARTIAL | chat, Brain audit, memory/conversation views, Provider configuration, and Search/privacy configuration | Vitest, TypeScript production build and existing Playwright flow | Search sources/clicks, tool-progress controls, remaining privacy/permission completeness |
| Game UX | PARTIAL | natural reply outbox and legacy waiting question delivery | Runtime/Fabric conversation evidence | External Brain progress and ASK_USER answer flow |
| Reliability | PARTIAL | WAL, leases, restart interruption, dedupe | restart/persistence/E2E tests | long-run, reconnect/backoff, leak/performance breadth |
| Installer | PARTIAL | Windows installer components exist | installer unit tests | clean-directory full product smoke |
| Migration | PARTIAL | versioned SQLite migrations | migration rollback/idempotency tests | configuration migration and upgrade backup proof |
| Upgrade | PARTIAL | terminal upgrade components exist | limited unit coverage | atomic end-to-end upgrade verification |
| Rollback | PARTIAL | rollback components exist | limited unit coverage | failed-upgrade full rollback smoke |
| Uninstall | PARTIAL | uninstall support exists | limited unit coverage | process/port/data retention clean smoke |
| Doctor | PARTIAL | diagnostics modules and health endpoints; live MCP negotiation/schema/permission probe | MCP Doctor fake-server contract test plus existing unit/CI coverage | Brain and Search protocol doctors |
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
