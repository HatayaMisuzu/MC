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
| 1 | Product truth, RC matrix, current docs, archive governance, documentation check | `NOT_STARTED` | Requires machine-readable product truth, cross-document negative tests, and all current/historical documents classified. |
| 2 | Personal evidence removal, privacy scanner, `.gitignore` resource boundary | `NOT_STARTED` | `docs/human-test/INSTANCE_AUDIT.md` contains a real drive path and local hashes; current history must not be rewritten. |
| 3 | Compatibility Pack total budget, manifest ambiguity, parser limits | `NOT_STARTED` | Verify the loader and add hostile archive/parser tests without enabling native execution. |
| 4 | Secret/dependency/CI/Gradle 9 hardening | `NOT_STARTED` | Expand deterministic secret coverage, review dependency verification, and classify every warning. |
| 5 | MCP token ACL, rotation, session and bootstrap-state hardening | `NOT_STARTED` | Verify Windows ACL behavior and fail-closed rotation across all old authority. |
| 6 | Link/reparse tests, game-message localization, bounded maintainability fixes | `NOT_STARTED` | Require real link evidence or an explicit runner-capability block; no broad rewrite. |
| 7 | Honest product limitations, Adapter matrix, Skill verification and follow-up entries | `NOT_STARTED` | Preserve Live/Human pending and distinguish supported capabilities from future architecture. |
| 8 | Complete clean-candidate local gates and exact release validation | `NOT_STARTED` | Every required command must be recorded as PASS/FAIL/NOT_RUN/BLOCKED with logs. |
| 9 | PR, exact candidate CI, ordinary merge, exact-main rebuild/CI, immutable tag and Release | `NOT_STARTED` | Target version/tag are `0.3.1` / `mcac-productization-baseline-0.3.1`; the 0.3.0 tag must remain unchanged. |
| 10 | Final full-audit report and item-by-item acceptance | `NOT_STARTED` | Produce `docs/product/MCAC_0.3.1_FULL_AUDIT_AND_REPAIR_REPORT.md`. |

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
