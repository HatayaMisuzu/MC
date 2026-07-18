# MCAC Typed Task Graph DSL

Version: `mcac-task-graph/1`

## Architecture boundary

```text
Task Graph Runtime = deterministic orchestration
External Brain = reasoning and planning
```

The external Brain chooses goals, strategy and graph revisions. MCAC validates and deterministically
executes an accepted graph. The graph layer is not an internal planner and cannot generate open-ended
goals, load code, invoke a shell, access the network, or access arbitrary files.

## Formats

`TaskGraphCodec` accepts bounded JSON or YAML documents. Documents are limited to 2 MiB, nesting and
scalar sizes are bounded, duplicate keys are rejected, and trailing documents/tokens are rejected.
Lua, JavaScript, Python, native binaries and system shell scripts are not supported.

## Required graph fields

```yaml
version: mcac-task-graph/1
id: stable-graph-id
inputs: {}
permissions: []
limits: {}
root:
  id: stable-root-node
  type: return
```

Every node has a graph-unique stable `id`. Supported input types are:

```text
string integer number boolean registry_item registry_block registry_entity position json
```

Execution limits may lower the product defaults but cannot exceed them. Evidence has both
`maxEvidenceEntries` and `maxEvidenceBytes` limits; the byte limit is at least 1024 and at most
524288 by default/product hard limit. Progress observations, Tool outcome evidence, checkpoints and
Runtime events all pass through this same rotating budget. An individual oversized entry is replaced
by an explicit `EVIDENCE_ENTRY_OVERSIZED` record containing its byte count and SHA-256 rather than
silently storing or truncating unbounded content.

## Node types

```text
sequence
call_tool
if
switch
repeat
while
retry
fallback
parallel
wait
ask_user
read_memory
suggest_memory
checkpoint
emit_progress
return
fail
```

`repeat` and `while` require `maxIterations`. `retry` requires `maxAttempts` and bounded
`backoffMillis`. `parallel` requires `maxConcurrency`. Unknown fields and unknown node types are
rejected instead of ignored.

`parallel` branches use one Runtime-owned shared pool, not a new pool per node. A graph may lower
`maxParallelNodes` from the hard maximum of four, and each node's `maxConcurrency` and branch count
must fit that graph budget. Nested `parallel` nodes are rejected so a parent cannot occupy the
bounded pool while waiting for child work.

## Expressions

Expressions allow only:

- field references such as `inputs.item`, `state.acquired`, and `outputs.scan.next`;
- literal array selection such as `outputs.scan.candidates[0].position`, with indexes limited to
  `0..255`;
- array length such as `outputs.scan.candidates.length`;
- string, number, boolean and null literals;
- `!`, `&&`, `||`;
- `==`, `!=`, `<`, `<=`, `>`, `>=`;
- parentheses.

`${...}` wrapping is optional. Expressions are parsed but never evaluated as source code. Function
calls, dynamic indexes, assignment, imports and arbitrary operators are rejected. Literal selection
also checks the actual array boundary at execution time.

## Observation composition

Tool observations can feed later generic Tools without a scenario-specific Handler:

```yaml
- id: scan
  type: call_tool
  tool: world.scan
  arguments: {block: "examplemod:blue_ore", radius: 8}
- id: has-candidate
  type: if
  condition: "${outputs.scan.candidates.length > 0}"
  then:
    id: navigate
    type: call_tool
    tool: movement.navigate
    arguments:
      x: "${outputs.scan.candidates[0].position.x}"
      y: "${outputs.scan.candidates[0].position.y}"
      z: "${outputs.scan.candidates[0].position.z}"
      dimension: "${outputs.scan.candidates[0].dimension}"
```

References inside Tool arguments and `return` values are validated before execution. Malformed,
dynamic or over-limit candidate selection is rejected explicitly instead of being treated as text.

## User questions

`ask_user` pauses the current execution without creating a plan or choosing a strategy:

```yaml
- id: confirm
  type: ask_user
  prompt: Continue with the externally selected action?
  options: [Yes, No]
  freeTextAllowed: false
- id: done
  type: return
  value: ${outputs.confirm.text}
```

`options` accepts one to three labels. When options are present, `freeTextAllowed` defaults to
`false`; without options it defaults to `true`. The accepted answer is persisted as the node output
with `text` and `optionId` fields. Web and game answer ingress resume only the execution that owns
the durable question. Ordinary chat that does not classify as an answer is not consumed by the
question.

## Limits

Graphs may lower these defaults but cannot raise them:

| Limit | Hard maximum |
|---|---:|
| Nodes | 256 |
| Depth | 16 |
| Loop iterations | 64 |
| Retries per node | 5 |
| Parallel nodes | 4 |
| Tool calls | 128 |
| Wall time | 1,800 seconds |
| Serialized state | 2 MiB |
| Evidence entries | 1,024 |

Limit exhaustion must become an explicit terminal observation when execution is implemented; it must
never become an unbounded loop.

## Current implementation state

The bounded codec, schema validator, safe expression parser and `task_graph.validate` Tool are locally
verified. Both JSON objects and bounded JSON/YAML `document+format` inputs use the production Tool
Gateway. Validation distinguishes DSL-valid nodes from nodes executable by the current Runtime and
binds each `call_tool` to the current `ToolDefinition.permission` and `inputSchema`. Required fields,
literal types/ranges/enums, and additional-property rules are checked before acceptance; exact safe
data references are deferred until their value exists. Unknown permission names are rejected.

`task_graph.execute` creates a persistent asynchronous execution. Session-owned
`task_graph.inspect`, `task_graph.pause`, `task_graph.resume`, and `task_graph.cancel` expose control
without adding planning behavior. `resume` also accepts an interrupted execution only after a
conservative recovery assessment succeeds. The deterministic core executes `sequence`, `call_tool`, `if`,
`switch`, `repeat`, `while`, `retry`, `fallback`, `parallel`, `wait`, `checkpoint`,
`emit_progress`, `ask_user`, `read_memory`, `return`, and `fail`. Safe expressions can read graph
inputs, persisted state, and prior Tool observations. Loop iterations receive stable scoped node and
Tool call IDs, and loop cursors are persisted. Parallel branches use real bounded concurrency; state
snapshots are serialized, pause/cancel reaches every active Tool call, and all active graphs share
the Runtime's four-worker parallel budget.
Timed `wait` nodes and retry backoff persist an absolute deadline, release the fixed Graph execution
worker, and are resumed by a separate bounded scheduler. Startup re-registers safely persisted
deadlines; pause/cancel invalidates their scheduled continuation, and the scheduler resumes only a
record that is still `WAITING` with the same deadline. Graceful Runtime shutdown lets a worker finish
persisting that boundary before stopping the scheduler, so restart cannot turn a committed wait or
retry backoff into a cancellation. Automatic `retry` accepts only subtrees whose current Tool
definitions are idempotent; validation rejects known non-idempotent effects and execution repeats
the check against live definitions before the first attempt. `task.wait` is a bounded one-node
convenience wrapper over this mechanism. `task.checkpoint` records a bounded external-Brain
checkpoint only at a persistent node boundary. Task control Tools are not callable from inside a
Task Graph, preventing recursive self-control from becoming an implicit planner or scheduler.
The result of `task_graph.cancel` preserves the external request `callId`. Execution timeout requests
cancellation of every active child Tool and waits for a bounded durable confirmation. Confirmed
cancellation is returned as `TOOL_TIMEOUT_CANCELLED`; if cancellation cannot be confirmed, the
execution is persisted as `RECONCILIATION_REQUIRED` and returned as
`TOOL_TIMEOUT_RECONCILIATION_REQUIRED` rather than being reported as a verified stop.
Immediately before Tool dispatch, resolved arguments are validated again against the same current
`ToolDefinition.inputSchema`. A schema mismatch fails with `TOOL_ARGUMENT_SCHEMA_INVALID` without
calling the Tool, so a graph cannot pass static validation and then bypass a changed or dynamically
resolved Tool contract.
Tool/wall-time/loop/concurrency budgets, bounded backoff/wait, uniformly rotated evidence, inputs,
variables, durable node outputs, and exact `${inputs.*}`, `${state.*}`, and
`${outputs.<node>.*}` references are enforced, including bounded literal array selection and length.

Migrations 11–15 persist every supported node, Tool, checkpoint, loop and parallel boundary, terminal
`return` values, all node outputs, and Task Graph-owned waiting questions. A safe pause resumes using
completed scoped node IDs, loop cursors, and immutable Tool results, so completed effects are not
repeated even when suspension occurs inside an iteration. A Runtime restart preserves safely waiting
and paused executions; an answered `ask_user` question resumes the original execution once, including
after restart. Startup still moves crash-left `READY` or `RUNNING` work to
`RECONCILIATION_REQUIRED`. A same-owner recovery resume rechecks the graph hash, persisted state
shape, current graph validity, Tool availability, permission and idempotency before continuing from
the durable boundary. An unconfirmed idempotent Tool may be reissued with its stable call ID; a
confirmed completed Tool result is reused without repeating the effect. Unknown non-idempotent
effects, changed Tool availability, malformed Evidence and graph-hash mismatch remain in
`RECONCILIATION_REQUIRED`. Persisted retry attempt cursors and backoff deadlines resume at the next
stable scoped call ID rather than replaying a prior attempt. Automatic retry of non-idempotent
effects is rejected rather than treated as reconciliation. Exact reconciliation of non-idempotent
effects inside loop scopes and live Mod status reconciliation are not yet claimed. A Tool transport or worker failure with
unknown effect is also persisted as `RECONCILIATION_REQUIRED` rather than being left `RUNNING` or
reported as a verified failure.
`read_memory` is implemented as a permission-bound convenience node over the generic
`memory.search` Tool; it requires `MEMORY`, filters by the declared memory kind, and retains
provenance and verification metadata in its observation. `suggest_memory` is a permission-bound
convenience node over `memory.suggest`. It accepts only `EPISODIC`, `WORLD`, or `PREFERENCE`, creates
a stable Task Graph-derived suggestion key, and writes only a `QUARANTINED` expiring candidate in
the separate suggestion store. It never writes a `MemoryFact` or verified World Memory.

## Representative E2E evidence

`runtimeFabricE2E` includes the first production-path representative graph. An authenticated
external MCP client submits a graph that searches the live Fabric Registry for an otherwise unknown
`mcac_registry_fixture` namespace, checks the length of the prior Tool output, selects its first
entry, and passes that Observation into `registry.describe`. The graph contains no namespace-specific
Java Handler and the Runtime does not decide what to search for or how to branch.

The resulting `evidence/representative-task-graph.json` is classified
`LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E` and records `liveModel=false`. It proves the external-client,
MCP, Task Graph, expression, Tool Gateway, authenticated Mod session, and live Registry path. It does
not prove a real Hermes/DeepSeek model selected the graph, a human played the scenario, or a formal
online Provider passed.

A second graph exercises a different boundary: a typed `position` graph input is passed to
`block.inspect`, then the exact live `${outputs.inspect.position.*}` Observation fields become the
arguments of the generic `movement.look` primitive. Acceptance requires the Fabric terminal evidence
to identify `VANILLA_ENTITY_LOOK`, so a graph that merely validates or returns a mocked body effect
does not pass.

A third external-client session first submits a graph whose `registry.describe` call omits a required
Tool argument. Runtime rejects it with the real `TOOL_INPUT_SCHEMA_INVALID` issue and does not repair
or plan around it. The external client supplies a corrected second revision under a new execution ID;
its declared `fallback` bypasses an explicit failed primary and returns the live Registry entry. This
tests graph correction ownership and deterministic fallback without turning Runtime into a Planner.
A fourth graph persists `ask_user`, verifies that the waiting question is bound only to its originating
Task Graph execution, and sends a deterministic automation answer through the normal user request
ingress. The same execution resumes and uses the answer to select a live Registry Tool branch. Its
evidence records `deterministicTestAnswer=true`; this is not a human answer or human-playtest claim.

The fifth graph reaches a persisted timed `WAITING` boundary after a live inspection. Its owning
external client pauses it, the production Runtime process exits normally and is replaced, and the
same Fabric body reconnects. Inspection proves the execution remains `PAUSED` after restart; only an
external `task_graph.resume` continues it into `movement.look`, whose terminal evidence again requires
`VANILLA_ENTITY_LOOK`. The five local representative structures are therefore complete, while remote
CI for their exact SHA, Live Brain verification, and human playtest remain separate gates in
`RC_COMPLETION_MATRIX.md`.

The same production-path test also records `evidence/primitive-equivalence.json`. One external-client
graph withdraws and restores one live chest item using the bidirectional `inventory.transfer`
primitive. A second graph opens that chest with `block.interact`, reads the live `menu.inspect`
Observation, passes `${outputs.inspectMenu.sessionToken}` into `menu.close`, and requires the exact
scoped capability to be consumed successfully. The later compatibility shortage chain still
withdraws and delivers the chest's original six items, which checks that the primitive round trip
did not manufacture, lose, or strand inventory. This is local deterministic external-client
evidence (`liveModel=false`), not Live Brain or human-play evidence.
