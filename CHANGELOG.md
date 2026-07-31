# Changelog

## Unreleased

- Repair the release documentation boundary: the staged package now ships only current
  user/operator and external-Brain integration documentation plus machine-readable product
  facts; repository-internal execution rules, historical reports and archived evidence are no
  longer bundled. New `tools/release-documentation-check.ps1` verifies required documents, every
  Markdown link, the release boundary and the README.txt/README.md equivalence against the staged
  and clean-extracted release, with negative tests covering deleted link targets, escaping links,
  forbidden files and missing user guides.
- Unify version/readiness wording (published frozen baseline vs. pending Live Brain/human-play
  evidence), the managed Runtime port range (`8766..8866`, management port +10000) and External
  Brain failure behavior across the README, product status, troubleshooting and bilingual user
  guides.
- Make the release starter `启动终端.cmd` launch `mcac.exe web --open-browser` as its name
  promises; `MCAC_NO_BROWSER=true` remains an unconditional safety veto.

## 0.3.1

- Converges the product version, readiness, Loader modes and managed Runtime port range through a
  machine-readable product-truth contract and negative documentation checks.
- Removes tracked personal instance-audit evidence from the current tree and replaces it with a
  privacy-safe template; historical Git objects are intentionally not rewritten.
- Repairs compatibility and Runtime documentation, current/historical status boundaries, release
  metadata and security/reproducibility gates found by the full-repository audit.

## 0.3.0

- Establishes Fabric 1.21.1 and Forge 1.20.1 Full Runtime Bridges; NeoForge 1.21.1 remains
  `LOCAL_ONLY`.
- Adds persistent typed Task Graph execution, primitive Tools, MCP, Memory/Search, quarantined
  generated Skills, Compatibility Host lifecycle, bilingual HTML terminal and release packaging.
- Freezes the automated baseline while retaining
  `LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING` and `HUMAN_PLAYTEST_PENDING`.

## 0.1.0-alpha

- 建立 Fabric 1.21.1、NeoForge 1.21.1 与 Forge 1.20.1 隔离构建目标。
- 建立独立协议/Pure Core、ServerPlayer 身体边界与 JVM Runtime。
- 增加 owner ACL、控制租约、行为状态、动作证据和安全门禁。
- 增加规则命令模式和可选 OpenAI-compatible Provider 配置。
