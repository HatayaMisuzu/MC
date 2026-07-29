# Compatibility/UI productization freeze status

Status: Superseded / Historical. This file is not current status. Current truth:
`docs/PRODUCT_STATUS.md` and `docs/RC_COMPLETION_MATRIX.md`.

Updated: 2026-07-27

This resumable checkpoint follows `MCAC_CODEX_产品化固定前主执行文件_v1.md`. It records
automation precisely: fixture, Replay, GameTest and browser automation are not Live Hermes or human
play. `docs/RC_COMPLETION_MATRIX.md` remains the only completion matrix.

## Repository checkpoint

- Base branch: `main`
- Working branch: `codex/forge-hermes-human-acceptance`
- PR: `#3`
- Previous pushed stable slice: `ca91fd7` — full Terminal localization and compatibility sources
- Candidate stable slice: this document's source SHA — bilingual real-backend browser and complete
  compatibility-pack button lifecycle

## Completed in this execution

- clean baseline, platform builds, all three Loader launch/GameTest suites, dual Runtime E2E,
  restart, multi-profile, disabled-launch, package, HTML startup, Golden Path and Web gates;
- bounded installer/profile lock stripes, bounded planning queues with saturation tests, bounded
  HTML-window tracking and the real Fabric drop-containment fixture correction;
- declaration-only Compatibility Host, exact environment fingerprinting, immutable Store, signed
  index, recovery journal, deterministic resolver, scoped authorization and management API;
- first real compatibility-pack fixture lifecycle including update, rollback and disable;
- crash-window recovery and security rejection coverage;
- compatibility management inside the existing authenticated local Terminal;
- `zh-CN` and `en-US` resources for the full Web surface, system-language default, persistent
  no-refresh switching, fail-fast missing-key behavior, localized API failure explanations with
  retained technical codes, and product terminology governance.

## Current local evidence

- Compatibility Host tests: pass.
- Terminal Java compilation: pass.
- Web production build: pass.
- Web unit/component tests: 10 files / 15 tests pass.
- Language switching test proves current component state survives an English/Chinese switch and
  the selection persists.
- Mojibake scan over non-test Web source: clean.
- Bilingual packaged-backend Chromium path: pass against the real Java Terminal and Runtime with an
  isolated PCL2 fixture and no `fetch` mocks.
- Historical freeze evidence covered install, evidence, index, activate, update, rollback,
  quarantine, disable and remove. The 2026-07-29 corrective audit supersedes that UI: manual
  evidence authoring is removed and current browser coverage proves honest `STAGING`.
- Fresh `zh-CN` and `en-US` screenshots: visually checked for overflow, stale Loader metadata and
  leaked `undefined` values.
- Current focused revalidation: Web lint, 10 test files / 15 tests, Web production build,
  Compatibility Host tests and Terminal tests all pass.

## Next step

Run the final clean-tree local candidate, release-integrity and exact-SHA remote gates.

## Support and evidence boundaries

| Target | Current mode |
|---|---|
| Fabric 1.21.1 | `FULL_RUNTIME_BRIDGE` |
| Forge 1.20.1 | `FULL_RUNTIME_BRIDGE` |
| NeoForge 1.21.1 | `LOCAL_ONLY` |

Dynamic native compatibility extension execution remains
`DYNAMIC_EXECUTION_NOT_OPEN`. The fixture proves framework behavior, not arbitrary third-party Mod
support.

Live Hermes and human play remain the deliberate post-freeze follow-ups
`LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING` and `HUMAN_PLAYTEST_PENDING`; neither is a merge gate.

- Merge allowed: `NO` (remaining automated productization gates are not complete)
- Productization baseline created: `NO`
- User-action blocker: `NONE`
