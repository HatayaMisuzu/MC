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

## Expressions

Expressions allow only:

- field references such as `inputs.item`, `state.acquired`, and `outputs.scan.next`;
- string, number, boolean and null literals;
- `!`, `&&`, `||`;
- `==`, `!=`, `<`, `<=`, `>`, `>=`;
- parentheses.

`${...}` wrapping is optional. Expressions are parsed but never evaluated as source code. Function
calls, indexing, assignment, imports and arbitrary operators are rejected.

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
binds each `call_tool` to the current `ToolDefinition.permission`.

`task_graph.execute` creates a persistent asynchronous execution. Session-owned
`task_graph.inspect`, `task_graph.pause`, `task_graph.resume`, and `task_graph.cancel` expose control
without adding planning behavior. The deterministic core executes `sequence`, `call_tool`, `if`,
`switch`, `repeat`, `while`, `retry`, `fallback`, `parallel`, `wait`, `checkpoint`,
`emit_progress`, `return`, and `fail`. Safe expressions can read graph inputs, persisted state, and
prior Tool observations. Loop iterations receive stable scoped node and Tool call IDs, and loop
cursors are persisted. Parallel branches use real bounded concurrency; state snapshots are
serialized, and pause/cancel reaches every active Tool call. Tool/wall-time/loop/concurrency budgets,
bounded backoff/wait, uniformly rotated evidence, inputs, variables, and exact `${inputs.*}`,
`${state.*}`, and `${outputs.<node>.*}` references are enforced.

Migrations 11–13 persist every supported node/Tool/checkpoint/loop boundary and the terminal
`return` value. A safe pause resumes using completed scoped node IDs, loop cursors, and immutable
Tool results, so completed effects are not repeated even when suspension occurs inside an iteration.
Runtime startup still moves crash-left work to `RECONCILIATION_REQUIRED`; automatic reconciliation
of an unconfirmed in-flight Tool is not yet claimed. A Tool transport/worker failure with unknown
effect is also persisted as `RECONCILIATION_REQUIRED` rather than being left `RUNNING` or reported
as a verified failure. `read_memory` is implemented as a permission-bound convenience node over
the generic `memory.search` Tool; it requires `MEMORY`, filters by the declared memory kind, and
retains provenance/verification metadata in its observation. `suggest_memory` and ASK_USER remain
tracked separately in `docs/RC_COMPLETION_MATRIX.md`.
