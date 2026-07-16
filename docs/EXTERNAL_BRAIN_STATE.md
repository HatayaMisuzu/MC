# External Brain productization state

Updated: 2026-07-16

## Current status

The repository is migrating to the External-Brain-first architecture defined by
`CODEX_EXECUTION.md`. This document tracks implementation evidence without using or
updating the Codex Goal UI.

Current milestone: `ASYNC_EXTERNAL_BRAIN_TOOL_LOOP_REPLAY_VERIFIED`

The release is not yet `READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST` because Search Gateway,
the complete generic Minecraft tool set, External Brain ASK_USER migration,
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
- `BLOCKED` is returned immediately with the last Fabric observation so the external Brain can
  ask the owner rather than waiting until timeout.
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

## Deposit to storage slice

- Added `inventory.deposit` / `DepositToStorage` only when the connected Fabric body
  reports the capability as `AVAILABLE_NOW`.
- Deposits use the opened vanilla container menu and validated PICKUP/right-click slot
  transactions. Production code does not edit either inventory or create items.
- The body verifies both the companion inventory decrease and container inventory increase
  before reporting success, and closes the container on completion, cancellation, or failure.
- Fabric GameTests prove withdraw/deposit item conservation and prove a completely full
  container returns `CONTAINER_FULL` without changing either inventory.

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
