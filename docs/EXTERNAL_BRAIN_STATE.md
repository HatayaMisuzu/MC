# External Brain productization state

Updated: 2026-07-16

## Current status

The repository is migrating to the External-Brain-first architecture defined by
`CODEX_EXECUTION.md`. This document tracks implementation evidence without using or
updating the Codex Goal UI.

Current milestone: `GENERIC_RESOURCE_BODY_PARTIALLY_VERIFIED`

The release is not yet `READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST` because Search Gateway,
the complete generic Minecraft tool set,
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

## Search Gateway slice

- Added `DisabledSearchProvider`, explicit non-Live `ReplaySearchProvider`, and a bounded
  HTTP provider implementation with environment-only credentials.
- Added `search.query`, `search.open`, `search.citations`, and `search.cancel` tools.
- A Brain can open only a source ID returned by the current Brain search session; the
  tool protocol accepts no arbitrary URL.
- Enforced public HTTPS source URLs, no redirects, text-only content types, response
  size and timeout limits, global allow/deny domains, and safe-search request fields.
- Queries containing credential-shaped values, player UUIDs, server addresses,
  coordinates, or local paths are rejected before reaching a provider.
- HTML is converted to text without executing JavaScript and is returned under the
  `UNTRUSTED_EXTERNAL_CONTENT` trust label. Prompt-injection-shaped page text is flagged.
- Replay integration now verifies Runtime -> Brain -> world observation -> search query
  -> source open -> Brain final response in one bounded turn.

## Typed memory management slice

- All four memory categories carry durable provenance in schema migration 7.
- External Brain tools can list/search typed memories and submit only unverified,
  expiring preference suggestions. They cannot write verified World facts or delete memory.
- Body observations remain the trusted source for verified container World memory;
  user edits are stored with `USER` provenance and take precedence over inference.
- Added authenticated loopback memory list/search/export, user correction, delete, and
  per-category clear operations. Secret-shaped values are rejected from Brain suggestions.
- Added bounded `world.locate_known_container`; it returns only verified container memories,
  exposes verification provenance/time, marks same- versus cross-dimension candidates, and
  filters unverified inferences instead of presenting them as world facts.

## Brain persistence and restart slice

- Schema migrations 8 and 9 persist external Brain sessions, each bounded tool call result,
  its durable Task/Behavior binding, lifecycle state, and delivery acknowledgement.
- Audits store tool arguments, verified observations, result codes, and lifecycle state;
  hidden model reasoning is intentionally not stored.
- Runtime startup atomically marks crash-left nonterminal calls `INTERRUPTED` with
  `RUNTIME_RESTARTED`. Hermes resumes the same remote session ID and receives only terminal
  observations not previously acknowledged; providers without resumable history open safely
  as a new session instead of pretending recovery succeeded.
- Normal cancellation and tool-budget exhaustion are durably recorded.
- Added authenticated `/brain/audit` inspection for UI/diagnostics and Replay coverage.

## Asynchronous Tool to Fabric loop slice

- Minecraft action tools now return `ACCEPTED` only to the Runtime coordinator. They are not
  returned to the Brain until the bound durable Task reaches `SUCCEEDED`, `FAILED`, `BLOCKED`,
  `CANCELLED`, or `INTERRUPTED`; `RUNNING` progress is persisted for audit.
- The binding uses one stable `brainSessionId + callId + taskId + behaviorId`. Stable command
  IDs and the existing command idempotency store prevent duplicate Fabric execution.
- Tool timeout dispatches a real cancellation and reports `INTERRUPTED`; concurrent controller
  cancellation reaches the active Tool Gateway without waiting for the Brain turn lock.
- Tool timeout waits briefly for durable Fabric cancellation confirmation and distinguishes
  confirmed `CANCELLED` from an honestly `INTERRUPTED` result.
- `PAUSED` is returned immediately as `BLOCKED`, and `RECONCILIATION_REQUIRED` immediately as
  `INTERRUPTED`, with the last Fabric observation so the external Brain can
  ask the owner rather than waiting until timeout.
- A repeated `brainSessionId + callId` reuses the audited result and never executes the Tool
  Gateway twice; reusing an ID with different tool input is rejected. Separate companion turns
  use isolated sessions and locks, while cancellation reaches an active tool without waiting for
  that companion's turn lock.
- The local Hermes Replay E2E proves `movement.navigate -> inventory.withdraw -> movement.return
  -> inventory.deliver -> FINAL_RESPONSE` against a real Fabric 1.21.1 GameTest body. Every step
  carries terminal Fabric evidence; the chest loses six ingots, the owner gains six, and the
  companion retains none. Evidence is written under `build/e2e-runtime/evidence/` and is
  explicitly `REPLAY_PASS`, never Live evidence.

## Game and Web ingress slice

- When an External Brain adapter is configured, Fabric `player_request` chat now enters
  the same bounded `runtime-primary` Brain controller used by the authenticated Runtime
  HTTP ingress. The Replay integration verifies that the game reply is sourced from
  `external-brain` and that no internal plan or task is created.
- The Web Terminal's companion request route now calls Runtime `/brain`, not `/agent`.
- Added an External Brain page showing adapter health, active controller, durable tool
  observations, typed-memory provenance, and the companion chat entry point.
- Web UI tests cover tool-audit rendering, memory provenance, and chat submission.
- Slow `/brain` and migration-only `/agent` calls run on a dedicated bounded executor;
  authenticated health, task inspection, cancellation, and diagnostics remain responsive
  while an external provider call is blocked.
- Durable game conversation events remain pending until Fabric confirms owner-visible
  delivery. Unacknowledged events retry on status updates, and the body deduplicates event
  IDs before displaying them so a retry cannot produce duplicate chat messages.
- This is Replay automation evidence only; no Live provider or human-playtest claim is made.

## External Brain ASK_USER core slice

- Hermes `mcac-brain/1` accepts a validated structured `ASK_USER` result. The
  OpenAI-compatible adapter exposes a bounded `ask_user` function that Runtime intercepts;
  it is never dispatched as a Minecraft tool.
- Schema migration 10 stores a question against exactly one legacy plan or external
  `brainSessionId`, with optional verified Task binding, 1–3 stable option IDs, free-text policy,
  context, answer, expiry, and lifecycle state.
- The same Brain session reuses the same active `questionId`. Identical answer retries are
  idempotent, conflicting second answers are rejected, and only one ANSWER event is recorded.
- Authenticated Web ingress and Fabric game chat classify answer, ordinary chat, goal change,
  and cancellation separately. Ordinary chat does not consume a bounded option question;
  cancellation clears it; a valid answer resumes the original Brain session without opening a
  competing session.
- Repository restart, coordinator interruption/resume, Hermes/OpenAI-compatible adapter,
  HTTP, and game-delivery Replay tests pass locally. The real Fabric 1.21.1 E2E now proves
  `6/16 -> ASK_USER -> deliver_partial -> same Hermes session -> navigate -> withdraw 6 -> return
  -> deliver -> FINAL_RESPONSE`; vanilla container/player/companion deltas prove conservation.
  This remains Replay evidence and is not a Live provider or human-playtest claim.

## Deposit to storage slice

- Added `inventory.deposit` / `DepositToStorage` only when the connected Fabric body
  reports the capability as `AVAILABLE_NOW`.
- Deposits use the opened vanilla container menu and validated PICKUP/right-click slot
  transactions. Production code does not edit either inventory or create items.
- The body verifies both the companion inventory decrease and container inventory increase
  before reporting success, and closes the container on completion, cancellation, or failure.
- Fabric GameTests prove withdraw/deposit item conservation and prove a completely full
  container returns `CONTAINER_FULL` without changing either inventory.

## Bounded world scan slice

- Added `world.scan` backed by the generic `ExploreArea` body capability. Inputs accept one
  namespaced block ID, a radius from 1 to 16, and an optional nearby center; arbitrary chunks,
  dimensions, scripts, and world edits are not accepted.
- Fabric scans at most 256 loaded positions per server tick and returns at most 64 unique
  candidates sorted by squared distance. The terminal Tool observation carries the exact block,
  dimension, coordinates, scanned-position budget, and candidate count.
- A real Fabric 1.21.1 GameTest proves the scan remains RUNNING immediately after dispatch,
  completes across later ticks, finds two placed diamond-ore candidates once each, and ranks the
  nearer candidate first. Route exploration and restart/unreachable breadth remain incomplete,
  so the matrix truthfully keeps Explore at `PARTIAL`.

## Vanilla dropped-resource collection slice

- Added `resource.collect` backed by `CollectResource`. Production finds only nearby live
  `ItemEntity` instances for the requested namespaced item, walks through normal player input,
  and invokes vanilla pickup interaction only within reach; it never creates an entity or edits
  an inventory.
- Dispatch rejects unavailable quantities unless partial collection is authorized, detects a
  full inventory, stops after a bounded no-progress window if drops disappear, and reports
  success only after the companion's verified inventory delta reaches the target.
- The first real Fabric test exposed drops falling out of an empty test structure and correctly
  returned `RESOURCE_LOST`; the repaired world supplies ordinary supporting blocks. The passing
  test proves movement, vanilla removal of a two-coal ItemEntity, inventory +2, and a terminal
  `COLLECT_COMPLETE` observation. Broader resources and restart/unreachable cases remain open.

## Vanilla bounded-vein mining slice

- Added `resource.mine_vein` backed by `MineResourceVein`, with a namespaced block, quantity
  1..32, explicit same-dimension origin, and bounded partial-work policy. Fabric discovers only
  connected six-neighbor matching blocks in loaded chunks and within normal player reach.
- Each block advances according to vanilla hardness and the held tool. The terminal mutation is
  `ServerPlayerGameMode.destroyBlock`; production never calls `setBlock`, creates drops, edits an
  inventory, or fabricates tool wear. Incorrect tools, changed/unloaded resources, rejected or
  unbreakable blocks, missing drops, full inventory, world change, and timeout stop explicitly.
- Completion waits until tracked real `ItemEntity` drops have been picked up through vanilla
  `playerTouch` and the total inventory delta covers the observed drop count. A real Fabric
  GameTest proves two connected diamond ores disappear, two diamonds enter the inventory, an
  iron pickaxe consumes at least two durability, and audit evidence records the vanilla server
  player game-mode path. Distant navigation, broader ores/tools, restart, and Brain E2E remain,
  so Mine is intentionally `PARTIAL` rather than complete.

## Craft item slice

- Added `item.craft` / `CraftItem` only when the connected Fabric body reports the
  capability as `AVAILABLE_NOW`.
- The body resolves server-side vanilla crafting recipes, calculates the bounded material
  delta, and uses PICKUP menu transactions to place ingredients and retrieve output.
- Both the personal 2x2 grid and a verified nearby crafting-table 3x3 menu are supported;
  cancellation and failure return staged inputs through vanilla QUICK_MOVE transactions.
- Fabric GameTests prove two logs become eight planks, three iron ingots plus two sticks
  become one iron pickaxe, and missing inputs return structured `MATERIALS_INSUFFICIENT`
  evidence without producing an item.

## Verification boundary

- Replay results are automation evidence only and must never be reported as Live.
- Adapter health is `CONFIGURED` until a real external service has been checked.
- The legacy internal `/agent` path remains temporarily available during migration;
  no new high-level planning behavior may be added to it.

## Pending external validation

- `LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING`
- `HUMAN_PLAYTEST_PENDING`
