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
- Execution contract: repository-root `CODEX_EXECUTION.md` (source repository only; it does not
  ship inside product release packages)
- [Primitive Tools](../PRIMITIVE_TOOLS.md)
- [Task Graph DSL](../TASK_GRAPH_DSL.md)
- [Bounded World Model](../WORLD_MODEL.md)
- [Event-driven replanning](../EVENT_DRIVEN_REPLAN.md)
- [MCP protocol](../MCP_PROTOCOL.md)
- [Agent Skill Workspace](../AGENT_WORKSPACE.md)
- [Compatibility Layer Engineering](../compatibility/MCAC_COMPATIBILITY_LAYER_ENGINEERING.md)
- [Terminal design](../CONTROL_TERMINAL.md)

Representative gameplay scenarios are acceptance cases, not product-specific Java Handler names.
New behavior should compose primitive executors, declarative Skills and externally authored typed
Task Graphs. Unknown Registry content remains data and must produce verified observations or an
honest unsupported result.

## Shared code and version bindings

Keep the existing source boundaries when adding a Minecraft version; a new version does not need
a new Runtime, planner, Tool catalog or copy of the deterministic controllers.

| Existing location | Responsibility |
| --- | --- |
| `protocol`, `runtime`, `core` | Version-independent wire contracts, capability validation, persistent tasks/graphs, events and World Model. Body-common and navigation-common are compiled and tested by pure-core with Java 17. |
| `minecraft/body-common`, `minecraft/navigation-common`, `minecraft/bridge-common` | Plain Java action/state machines, navigation policy, identity/event normalization and connection lifecycle. No Minecraft version branches or Loader imports. |
| `minecraft/navigation-minecraft-common`, `minecraft/platform-bootstrap-common` | Minecraft API bindings that currently compile unchanged on both supported versions. Share only APIs verified against both targets. |
| `minecraft/platform-1.20.1-common`, `minecraft/platform-1.21.1-common` | Version-specific player/menu/item/NBT access and the existing Body lifecycle. Translate API differences here while preserving the shared outcomes and budgets. |
| `minecraft/fabric-1.21.1`, `minecraft/forge-1.20.1`, `minecraft/neoforge-1.21.1` | Loader startup/events, authenticated bridge, metadata and isolated dependencies/toolchain. NeoForge remains `LOCAL_ONLY`. |

Prefer a small adapter at an actual API difference over speculative interfaces or version checks
inside Runtime. A future target reuses the shared engines, implements the required bindings and
advertises only capabilities demonstrated on that connected Body. Registry IDs remain data;
neither an unknown namespace nor a declared compatibility pack implies verified behavior.

Minecraft fixtures belong in Loader test source sets. Fabric and Forge Body tests use
`src/gametest`; Forge attaches these classes to the product's development module so
package-private access does not create a split package. Forge unknown-Mod fixtures use the
separate `src/gametestmod` test mod. Tests must
not ship in the production JAR. World Model and event-replan harness entries require their
dedicated Runtime processes and are selected by the corresponding `tools/*-tests.init.gradle`.
Temporary logs, test worlds and evidence stay in ignored `build` or
`artifacts/codex-verification`; user worlds are never test cleanup targets.

## Change and release workflow

Keep implementation work focused on a coherent product change and verify only the affected risk
locally. Draft PR updates use Layer 1 fast validation. Layer 2 Windows integration runs when a
relevant PR is opened as non-Draft or deliberately moved to Ready, and refreshes on later pushes
while that PR remains non-Draft. Layer 3 is weekly or manual and owns Heavy Loader/Runtime coverage,
CodeQL, Gradle compatibility, and release candidates.

Ordinary commits and merges do not create candidates. To create one release candidate, manually
dispatch `.github/workflows/minecraft-heavy.yml` from `main` with `mode=candidate`. That single run
executes every Layer 3 prerequisite and then rebuilds and verifies the exact-main package before
uploading the only RC artifact. A separate `mode=audit` run is diagnostic coverage, not a required
first half of candidate creation.

Before that explicit candidate boundary, run only applicable tasks documented in the root
[README](../../README.md) and update the sole [RC matrix](../RC_COMPLETION_MATRIX.md) when product
truth changes. Local, Replay, GameTest, Live-provider and human evidence must retain their exact
labels. Do not create status-only commits to copy run IDs or repeat complete suites after a focused
fix.

The root Loader tasks preserve `--offline` in their nested builds and reuse incremental outputs;
they no longer run an unconditional `clean` for each Loader. Run shared checks once, then the
affected Loader/Runtime chains serially. Diagnose the first failed assertion or process before
rerunning it; a focused fix does not restart every passed scenario. An explicit clean build remains
available when diagnosing stale generated output.
