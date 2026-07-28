# MCAC 0.3.1 audit and repair tracker

Status: Active execution tracker
Updated: 2026-07-28

This file records the 0.3.1 repair queue and evidence locations. It is not a completion matrix.
[`docs/RC_COMPLETION_MATRIX.md`](../RC_COMPLETION_MATRIX.md) remains the only authoritative
completion matrix.

## Reality-check baseline

| Field | Value |
|---|---|
| Starting `main` | `ec56aca171f4bc7ffb43eca09cad07678b225163` |
| Starting `origin/main` | `ec56aca171f4bc7ffb43eca09cad07678b225163` |
| Working branch | `codex/mcac-0.3.1-audit-repair` |
| Initial worktree | Clean; no user changes found |
| Previous immutable tag | `mcac-productization-baseline-0.3.0` |
| Previous tag target | `ec56aca171f4bc7ffb43eca09cad07678b225163` |
| Local audit evidence | `artifacts/codex-verification/0.3.1-audit/` (ignored, never released) |

## Full tracked-file inventory

The initial inventory contains all 649 tracked files:

- 643 text files and 6 binary assets;
- 48 Markdown files;
- 435 Java files;
- 49 TypeScript/TSX files;
- 45 JSON/YAML/TOML/properties files;
- 50 Gradle/PowerShell/CMD/workflow files;
- 107 files initially classified as release-payload sources.

The ignored evidence directory contains the required tracked-file list, classification CSV,
language/config/script lists, release-source list, binary SHA-256 review, raw pattern matches, and
initial match classification. Binary assets are reviewed by origin, hash, metadata, and package
position rather than meaningless decompilation.

## Reconstructed work queue

| Phase | Scope | Status | Current evidence or exit condition |
|---:|---|---|---|
| 0 | Reality check, complete inventory, full-tree pattern scan | `COMPLETED` | All 649 files are classified and all 643 text files are in the scan population. Initial matches are classified as current-fact review, execution/history review, test fixture, or privacy/path review. The scan confirms stale RC readiness, tracked personal instance evidence, old version/status text, historical files without visible status markers, and the broad `data/` ignore rule. |
| 1 | Product truth, RC matrix, current docs, archive governance, documentation check | `COMPLETED` | `PRODUCT_TRUTH.json`, the 0.3.1/readiness/Loader/port convergence, visible historical markers, current release-document selection, and a deliberately contradictory negative fixture pass `documentationCheck`. |
| 2 | Personal evidence removal, privacy scanner, `.gitignore` resource boundary | `COMPLETED` | The current tree replaces the real instance audit with a safe template, removes remaining personal paths, scans tracked documentation, scopes `/data/` to the repository root, and verifies Fabric/Forge/NeoForge resource probes are not ignored. Git history is intentionally unchanged. |
| 3 | Compatibility Pack total budget, manifest ambiguity, parser limits | `COMPLETED` | Loader counts actual inflated bytes against 16 MiB, rejects dual manifests, applies bounded Jackson/SnakeYAML constraints and forbids aliases; focused and full compatibility-host tests cover hostile compressed, document and truncated-stream inputs. |
| 4 | Secret/dependency/CI/Gradle 9 hardening | `COMPLETED` | Deterministic tracked-tree/diff scanning plus a pinned Gitleaks CI job cover common key/token/private-key forms with exact fixture allowlists; npm/Gradle/Action pins, four wrapper checksums, 16 lockfiles and four verification metadata sets are enforced; project-owned internal/deprecated Gradle APIs were removed, Gradle 8.14.5 warning check has zero matches, and the SHA-verified Gradle 9.6.1 full shared `check` passes. |
| 5 | MCP token ACL, rotation, session and bootstrap-state hardening | `COMPLETED` | Shared fail-closed owner-only file enforcement covers Runtime, Mod and browser state on Windows/POSIX; atomic writes are reverified, bootstrap state is removed on exit, and MCP sessions persist across same-token restart but are hash-bound to the pairing generation so rotation rejects old bearers without revealing scope differences. Focused Windows ACL, transaction rollback/success, database migration/restart/rotation, cross-scope and Web lifecycle tests pass. Independent Controller PKI and full user-to-Companion ACL remain a documented separate architecture item. |
| 6 | Link/reparse tests, game-message localization, bounded maintainability fixes | `COMPLETED` | Real local Windows junction tests cover Workspace, backup/restore, installer/backup and Compatibility Store boundaries without modifying outside targets; Linux retains real file/directory symlink coverage, and Windows CI sets `MCAC_REQUIRE_WINDOWS_LINK_TESTS=1` so unavailable file/dir symlink creation fails explicitly as `BLOCKED_BY_RUNNER_CAPABILITY`. Shared en-US/zh-CN resources replace corrupt mixed command/help/chat-prefix text, key parity is wired into `check`, and all three `0.3.1` Loader artifacts contain both language files. The legacy `/agent`, game-chat fallback and CLI no longer grant internal planning authority. Full impacted module suites, docs/localization/dependency gates and three Loader builds pass; exact hosted Windows symlink evidence is deferred only to Phase 9 CI. |
| 7 | Honest product limitations, Adapter matrix, Skill verification and follow-up entries | `COMPLETED` | The real Live guide/CLI chain now activates the configured external Brain after a bounded Runtime restart without storing its token; Doctor's exact three-scenario Live boundary and both pending labels remain. A validation-gated Adapter matrix records honest Hermes/OpenAI-compatible/Replay differences. Navigation route construction, the first real Compatibility Pack and broader survival Skills have explicit future acceptance entries; the exact six current Skills remain schema/tool/permission validated and executable. |
| 8 | Complete clean-candidate local gates and exact release validation | `IN_PROGRESS` | Phase 7 focused Runtime, built-in Skill and Compatibility Host tests plus documentation validation pass. Clean `check`/platform builds and all Loader launches pass. The first combined GameTest run exposed a Forge fixture that counted scheduler callbacks instead of real server ticks and could exhaust its nominal 480-tick retreat budget early; it now uses an unchanged 480-server-tick absolute deadline and retains the exact three-block/six-block assertions. Later Fabric runs exposed concurrent tiny-template contamination in lifecycle routing, block placement, four navigation cases, live entity observation, the asynchronous menu-session inventory delta and item-use projectile lookup. Lifecycle, placement, navigation and menu-session cases now use separate forced high-altitude arenas with real ServerPlayer teleport/chunk tickets; live observation stays inside the managed structure and uses the dedicated attributed unknown-namespace `watcher`; item-use no longer clears a neighboring 9×6×9 volume, launches inside its own vertical column, crosses the entity-index tick boundary, verifies exactly one body-owned projectile and removes it. These cases retain exact index/visibility/ownership/Tool identity assertions and the corrected slices pass twice consecutively. The menu failure preserved the exact five-item quick-move assertion and proved two neighboring real stone drops had been collected. A later Forge run proved the separate navigation corridor still admitted underground lava through its open top/end and killed the Player body; the now-sealed three-block-high arena retains every door/dynamic-wall/entity/arrival assertion and Forge 4/4 passes twice consecutively. The first Fabric Runtime E2E also exposed one stale call to the intentionally retired internal `/agent`; the current E2E asserts its 410 closure and retains the separate Hermes Replay ASK/tool/final-observation chain, which passes. The first multi-Profile run then exposed unsupported standard-stream redirection left in release staging after the Provider-compatible Exec migration; release identity now uses Provider-owned output with the same nonzero-exit enforcement, and the corrected packaging/multi-Profile gate passes. Remaining full command-by-command gates and the final clean-candidate restart are in progress. |
| 9 | PR, exact candidate CI, ordinary merge, exact-main rebuild/CI, immutable tag and Release | `NOT_STARTED` | Target version/tag are `0.3.1` / `mcac-productization-baseline-0.3.1`; the 0.3.0 tag must remain unchanged. |
| 10 | Final full-audit report and item-by-item acceptance | `NOT_STARTED` | Produce `docs/product/MCAC_0.3.1_FULL_AUDIT_AND_REPAIR_REPORT.md`. |

Phase 8 Forge reconstruction additionally found that one long lifecycle GameTest accumulated its
retreat, navigation, water, climb and follow coordinates beyond the initially sealed corridor.
Recorded failures included generated-world `ENVIRONMENT_HAZARD` death, follow `STUCK`, transient
`inWall` damage under a redundant low inner roof, a remote coal entity excluded from the
GameTest-managed index, and later cleanup of the formerly 4.5-block-away coal before pickup. Each
long movement stage now uses a separate forced high-clearance sealed arena with unchanged targets
and budgets; entity-backed collection returns to the managed origin, remains 2.5 blocks away so
real movement is required, and waits on real server ticks. The corrected Forge 4/4 suite passes
twice consecutively.

The first exact-candidate Runtime/Fabric E2E returned a nonzero child exit after the earlier Gradle
Provider migration, but the shared helper had suppressed both child streams, making the original
external-orchestration cause unrecoverable. It is recorded as
`UNDIAGNOSABLE_DUE_SUPPRESSED_CHILD_OUTPUT`, not assigned a fabricated cause. The helper now
forwards captured stdout/stderr before enforcing normal exit, and the complete E2E passes twice
consecutively with full child evidence.

## Evidence rules

- Replay, Fake, Mock, Fixture, GameTest, dedicated-server automation, and real-backend browser
  tests retain their exact evidence class.
- No external credential is requested or used.
- Intermittent failures require root-cause classification; repeated execution alone is not a fix.
- Each stable phase is tested, reviewed, committed, and pushed before the next phase.
- Current-tree privacy cleanup does not claim destructive Git-history cleanup.

```text
LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING
HUMAN_PLAYTEST_PENDING
```
