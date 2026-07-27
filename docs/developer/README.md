# MCAC developer documentation

MCAC is a deterministic Minecraft runtime beneath an external high-level Brain. Development-time
tools may build this repository; the runtime Brain never receives shell, Git, compiler, arbitrary
filesystem, secrets or direct world mutation.

Start here:

- [Architecture and authority](../ARCHITECTURE.md)
- [Execution contract](../../CODEX_EXECUTION.md)
- [Primitive Tools](../PRIMITIVE_TOOLS.md)
- [Task Graph DSL](../TASK_GRAPH_DSL.md)
- [MCP protocol](../MCP_PROTOCOL.md)
- [Agent Skill Workspace](../AGENT_WORKSPACE.md)
- [Compatibility Layer Engineering](../compatibility/MCAC_COMPATIBILITY_LAYER_ENGINEERING.md)
- [Terminal design](../CONTROL_TERMINAL.md)

Representative gameplay scenarios are acceptance cases, not product-specific Java Handler names.
New behavior should compose primitive executors, declarative Skills and externally authored typed
Task Graphs. Unknown Registry content remains data and must produce verified observations or an
honest unsupported result.

Before submitting a candidate, run the applicable tasks documented in the root
[README](../../README.md), then update the sole [RC matrix](../RC_COMPLETION_MATRIX.md). Local,
Replay, GameTest, Live-provider and human evidence must retain their exact labels.
