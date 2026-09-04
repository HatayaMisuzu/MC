# Event-driven bounded replanning (Feature 8)

The external Brain remains the only planner. The Runtime only filters events, freezes an existing
execution, settles its exact child task, validates a Brain-authored revision and resumes it.

## Admission and local recovery

| Trigger | Local handling / semantic escalation |
|---|---|
| Fatal danger | Existing finite safety handling wins for locally handled low health/threats. Death, fire, lava and low air retain their existing emergency escalation and cannot be masked by a local-handling flag. |
| Invalid route | Navigation owns bounded path repair, obstacle/stuck handling. Its exhausted `TASK_BLOCKED` / `TASK_FAILED` edge requests a new plan. |
| Full inventory | The stable, confirmed `INVENTORY_FULL` edge may require an external change to remaining work. Initial resource shortage is not a plan change. |
| Broken tool | The executing primitive owns available local recovery; exhausted failure/blocking (including broken/missing-tool codes) escalates. |
| Invalid key target | Ordinary target/block/container/death observations stay with local effect verification. Exhausted task failure, or an explicit `localRecoveryExhausted` target edge, escalates. Mining, collecting, attacking or depositing must not replan because of their own expected effects. |
| Inserted instruction | Authenticated immediate instructions / goal modifications amend remaining goals or priorities, preserving the immutable original goal and completed work. Explicit cancel is still available separately. |

Progress, start, pause, resume and initial item-deficit events do not request replanning. A retained
`localSafetyHandling` or active/completed local-recovery indication prevents Brain interruption for
recoverable events, not the emergency hazards above.
World events bind through the exact persisted Body task command to the owning graph; they do not
grant access to another companion/controller/session.

Successful completion wakes only a Brain still waiting on that exact execution; other clients
retain their durable receipt and conversation feedback without opening an unrelated Brain session.
Failed event dispatch stops after at most three claimed attempts, and a permanent Brain budget
failure stops immediately. Suppressed events remain inspectable; restoring a provider does not
silently replay an exhausted event. This does not expand the graph's separate replanning budget.

## Request and tool contract

Graphs started during an authenticated Brain user turn capture the original user text automatically.
An external MCP graph author supplies `provenance.originalUserGoal` (1–4096 characters) when it wants
automatic event replanning. A legacy/unbound graph with no persisted user goal is not assigned an
invented goal by the Runtime.

The request includes the original goal, accumulated instruction amendments, execution ID, graph
epoch, frozen snapshot revision, completed nodes, current failure/event, outputs and bounded World
Model context. Requests are capped at 64 KiB; a large graph is obtained through `task_graph.inspect`
instead of being duplicated into the request. World context retains source/freshness metadata.

The Brain calls:

```json
{
  "name": "task_graph.replan",
  "arguments": {
    "executionId": "existing-execution",
    "requestId": "the-admitted-event-id",
    "epoch": 0,
    "expectedRevision": 17,
    "graph": { "version": "mcac-task-graph/1", "id": "unchanged-id", "permissions": [], "root": {} }
  }
}
```

`graph` is the updated **full** graph, not a new execution. The abbreviated example above is not an
executable graph. The normal DSL, Tool availability, schema and permission checks still apply.
Graph metadata, input declarations, permissions and limits cannot change. Completed nodes and their
execution scopes remain immutable; attempted IDs cannot acquire a different meaning, and removed
IDs cannot be recycled. Existing completed nodes, tool receipts, variables, outputs and evidence are
retained. Partially executed non-sequence control subtrees are retained intact; the Brain can amend
unexecuted branches at a safe sequence boundary without replaying their completed effects.

During a replan turn the Brain may inspect context and rewrite only the bound execution. It cannot
start unrelated Tools or a replacement execution. No shell/filesystem/network authority is added.

## Durability and stopping rules

Migration 34 adds bounded `replan_json` state to the existing execution row. The phases are
`REQUESTED` (pause pending), `WAITING_BRAIN`, `APPLIED` (resume pending), `RESUMED` and `BLOCKED`.
Graph replacement and epoch advancement are one SQL compare-and-set. A retry with the same request,
epoch and graph returns the already-applied receipt; conflicting/stale responses are rejected.

Each execution admits at most four semantic replan requests and at most two provider deliveries per
event. Delivery is charged before calling the provider. Same-event deduplication and retired node IDs
survive restart, and old events predating the applied revision are ignored. Normal provider aggregate
request/token/time budgets remain in force as well.

Restart requeues durable pending requests (including inserted instructions) into the existing event
queue without resetting budgets or refreshing their observation timestamp. Pre-death events are not
resurrected across a later death. Dispatch waits for connected Body capabilities. Applied-but-not-
resumed revisions resume through existing reconciliation; unknown effects fail closed. Blocked or
paused child tasks must be cancelled by exact command/task identity, with terminal observation and
control release confirmed before the next plan can run. Missing/uncertain confirmation or exhausted
budgets retain a visible blocked replan; ordinary resume cannot bypass it. User cancellation remains
available.

The single Feature 8 closeout audit checked the explicit requirements, real postconditions, safety,
cancellation/recovery and both bridge variants. It closed stale-event pre-pause, exact child-command
cancellation, new-instruction routing and uncertain-effect preservation gaps. No new planner or
runtime authority was introduced. The route fixture disables ambient spawning and isolates its local
arena before the tested execution; it does not disable product threat handling.

## Evidence

`EventDrivenReplanTest` is UNIT / SQLite INTEGRATION / REPLAY evidence for rewrite, ownership,
scope preservation, event loops, budgets, instructions, child cancellation and restart windows.
`RuntimeToolGatewayTest` checks the real cancellation command and exact durable binding.
The final affected Runtime regression on 2026-09-03 passed 137 tests across 20 suites, with no
failures, errors or skips (TaskGraph, Brain, events, Gateway, database and HTTP/WebSocket boundaries).

`tools/event-replan-e2e.py` runs a local deterministic `mcac-brain/1` provider fixture through the
production Hermes adapter and Runtime/Bridge into Fabric 1.21.1 or Forge 1.20.1. The generated arena
contains one dropped diamond and a destination chest; the fixture obstructs an intermediate waypoint
only after real collection. The original graph must block locally, request one Brain rewrite, travel
through a real detour and deposit the same diamond in the original chest. The harness checks the
durable graph, one collection receipt, one epoch and the real chest postcondition.

This is **REAL_MINECRAFT_GAMETEST + RUNTIME/BRIDGE + REPLAY_BRAIN**, not LIVE_PROVIDER or HUMAN_PLAYTEST.
Generated evidence is under `artifacts/codex-verification/event-replan-<loader>-evidence.json`.
Final current-code chains on 2026-09-03 passed Fabric 1.21.1 (1/1 GameTest) and Forge 1.20.1
(2/2, including its unknown-Mod fixture). Both persisted `SUCCEEDED`, epoch 1, `RESUMED`, one replan,
one collection receipt and one real diamond in the original chest. LIVE_PROVIDER and HUMAN_PLAYTEST
were NOT_RUN. No subsequent execution file or release candidate was started.
The Forge harness fixture lives in `src/gametest` and is excluded from the ordinary test run;
the focused init script explicitly opts it in. Fabric registers it only in the focused GameTest
descriptor. Neither Loader includes this fixture in its production JAR.
