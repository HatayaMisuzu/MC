# MCAC documentation

Updated: 2026-07-31

This is the current documentation entry point. It separates product status, evidence, user
instructions, developer contracts and historical logs so one old milestone cannot override the
current candidate.

## Current authority

- [Product status](PRODUCT_STATUS.md): reader-facing phase and support matrix.
- [Frozen-release product truth](product/PRODUCT_TRUTH.json): 0.3.1 version, readiness, Loader modes
  and managed Runtime port range used by documentation validation.
- [Current-main truth](product/CURRENT_MAIN_TRUTH.json): post-release scope, frozen baseline link,
  exact-SHA authorities, and external evidence that remains pending.
- [Brain Adapter capability matrix](product/BRAIN_ADAPTER_CAPABILITIES.json): machine-readable
  Hermes, OpenAI-compatible and Replay differences.
- [RC completion matrix](RC_COMPLETION_MATRIX.md): the only completion/evidence matrix.
- [Known limitations](../KNOWN_LIMITATIONS.md): current explicit limitations.
- [Architecture](ARCHITECTURE.md): product responsibility and trust boundaries.

Productization baseline/closeout and repository-productization audit files under `docs/product/`
are repository-internal evidence and do not ship in release packages; they never replace the
RC matrix as a current status source.

## Users

- [中文用户指南](user/USER_GUIDE.zh-CN.md)
- [English user guide](user/USER_GUIDE.en-US.md)
- [Troubleshooting](TROUBLESHOOTING.md)
- [Live Brain and human playtest guide](LIVE_BRAIN_HUMAN_PLAYTEST.md), used only after the
  automated baseline is frozen.

## Compatibility and developers

- [Current compatibility support](COMPATIBILITY.md)
- [Compatibility Layer Engineering](compatibility/MCAC_COMPATIBILITY_LAYER_ENGINEERING.md)
- [Developer documentation](developer/README.md)
- [Task Graph DSL](TASK_GRAPH_DSL.md)
- [MCP protocol](MCP_PROTOCOL.md)
- [Primitive Tools](PRIMITIVE_TOOLS.md)
- [Agent Workspace](AGENT_WORKSPACE.md)
- [Built-in Skill scope](product/BUILTIN_SKILL_SCOPE.md)
- [First real Compatibility Pack acceptance](compatibility/FIRST_REAL_PACK_ACCEPTANCE.md)

## Repository-internal execution and history

The following exist only in the source repository and are **not** included in product release
packages: the execution tracker under `docs/execution/`, the archive index under `docs/archive/`,
the root execution contract `CODEX_EXECUTION.md`, and agent rules in root `AGENTS.md`. They are
historical or agent-execution evidence and do not replace the RC matrix.
