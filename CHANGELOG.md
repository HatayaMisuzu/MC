# Changelog

## Unreleased

- Make installer recovery ownership-aware across the PREPARED and post-move journal windows, and
  retain the prior managed manifest inside each rollback point so verify, uninstall and later
  updates remain valid after rollback. Trim OpenAI-compatible history by complete Tool-call groups
  with full pre-turn rollback on request failure. Isolate Web resource and Brain-form state by
  instance, accept the Runtime's durable timed-WAITING resume state in the product flow, and update
  the development-only Browserslist chain from 4.28.6 to 4.28.9 and Vitest from 4.1.10 to 4.1.11.
- Mark all bundled jpackage launchers as UTF-8-aware so the Windows package resolves its bundled
  Runtime when extracted below a Unicode path that the host ANSI code page cannot represent. The
  final artifact test now includes such a cross-code-page path instead of relying on the host locale.
- Preserve explicit primitive attack targets during bounded threat evaluation on both Minecraft
  versions; low health, explosive threats and multiple hostiles still preempt execution.
- Deliver durable Task Graph lifecycle feedback immediately, including completion without another
  game status packet, while keeping progress/checkpoint callbacks off the synchronous conversation
  delivery path so pause/resume/cancel controls remain responsive. The Terminal now follows an
  asynchronously accepted graph control to its durable state before clearing the control status,
  avoiding stale `RUNNING` displays. Restore NeoForge's existing shared navigation/event bindings.
- Prevent unrelated task-completion events from opening Brain sessions, stop failed event dispatch
  after bounded attempts, and announce reconnected Bodies before their first authenticated events.
- Integrate bounded daily actions, survival navigation, durable player/world/inventory events,
  arbitrary entity interaction, small blueprint construction, continuous combat/recovery,
  a persisted World Model and external-Brain event replanning on Fabric 1.21.1 and Forge 1.20.1.
  Shared Java controllers retain version-specific Minecraft adapters; NeoForge remains `LOCAL_ONLY`.
- Keep admitted critical notifications deliverable without executable Body capabilities while
  graph replanning waits for its connected Tool contract. Isolate Forge GameTests from production
  artifacts, preserve offline nested Loader builds and remove repeated implicit clean builds.
- Include World Model and replanning contracts in the product documentation and document the
  existing shared/version/Loader boundaries for future targets. The frozen 0.3.1 release remains
  unchanged; new capability evidence is automated and does not establish Live/Human coverage.
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
