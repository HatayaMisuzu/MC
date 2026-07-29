# MCAC developer documentation

## Reproducible dependency updates

All production npm declarations are exact and `package-lock.json` is authoritative. The shared
Gradle build and all three isolated Loader builds use dependency lock state, SHA-256 dependency
verification metadata, and wrapper distribution checksums. After an intentional reviewed
dependency change, regenerate only the affected lock/verification files with `--write-locks` and
`--write-verification-metadata sha256`, inspect the coordinate and checksum diff, then run
`dependencyPinningCheck`, the affected build and the Gradle 9 compatibility probe. Do not disable
verification globally to make an unreviewed artifact resolve.

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

## Change and release workflow

Keep implementation work focused on a coherent product change and verify only the affected risk
locally. Draft PR updates use Layer 1 fast validation. Layer 2 Windows integration runs once when a
relevant PR is opened as non-Draft or deliberately moved to Ready; it is not a per-push loop.
Layer 3 is weekly or manual and owns Heavy Loader/Runtime coverage, CodeQL, Gradle compatibility,
and release candidates.

Ordinary commits and merges do not create candidates. To create one release candidate, manually
dispatch `.github/workflows/minecraft-heavy.yml` from `main` with `mode=candidate`. The workflow
first requires every Layer 3 audit job to pass, then rebuilds and verifies the exact-main package
before uploading the only RC artifact.

Before that explicit candidate boundary, run only applicable tasks documented in the root
[README](../../README.md) and update the sole [RC matrix](../RC_COMPLETION_MATRIX.md) when product
truth changes. Local, Replay, GameTest, Live-provider and human evidence must retain their exact
labels. Do not create status-only commits to copy run IDs or repeat complete suites after a focused
fix.
