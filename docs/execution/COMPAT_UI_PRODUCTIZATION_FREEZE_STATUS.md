# Compatibility/UI productization freeze status

Updated: 2026-07-27

This resumable checkpoint follows `MCAC_CODEX_产品化固定前主执行文件_v1.md`. It records
automation precisely: fixture, Replay, GameTest and browser automation are not Live Hermes or human
play. `docs/RC_COMPLETION_MATRIX.md` remains the only completion matrix.

## Repository checkpoint

- Base branch: `main`
- Working branch: `codex/forge-hermes-human-acceptance`
- PR: `#3`
- Latest completed stable slice: `dc4d530` — Compatibility Host lifecycle and Terminal page
- Previous stable slice: `16fd1c8` — productization safety/reliability hardening

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

## Next step

Exercise compatibility and high-frequency product flows through the real local Terminal backend,
record the button/backend/result matrix and both-language screenshots, then continue documentation
governance and final candidate gates.

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
