# Compatibility Host vertical slice — 2026-07-27

Status: Superseded / Historical. This file is not current status. Current truth:
`docs/PRODUCT_STATUS.md` and `docs/RC_COMPLETION_MATRIX.md`.

## Outcome

The first runnable compatibility-layer loop is implemented in the existing MCAC product. It does
not add an internal planner and does not rewrite the Fabric, Forge or NeoForge bodies.

Implemented:

- bounded, no-extraction `.mcac-compat` archive loading with strict entry, path, hash and document
  validation;
- immutable content-addressed Pack Store, signed active index, transaction journal, prior-index
  snapshots and restart recovery;
- exact Profile/instance/Store/operation/expiry/risk authorization grants;
- bounded environment fingerprints for Minecraft, Loader, Java, Mods, configuration, scripts,
  datapacks and modpack manifests;
- deterministic base → Loader → Mod → patch → overlay → instance composition;
- high-risk suppression for stale, provisional and structural matches;
- install, record-evidence, index, activate, deactivate, update, patch, rollback, quarantine,
  remove and export management operations;
- built-in descriptors for Fabric 1.21.1 Full Bridge, Forge 1.20.1 Full Bridge and NeoForge
  `LOCAL_ONLY`;
- a compatibility page inside the existing authenticated local Terminal, using its normal
  plan/confirm/progress/result flow;
- the full compatibility engineering contract at
  `docs/compatibility/MCAC_COMPATIBILITY_LAYER_ENGINEERING.md`.

Dynamic native extension execution is intentionally closed and reported as
`DYNAMIC_EXECUTION_NOT_OPEN`.

## Local evidence

- `:compatibility:compatibility-host:test` — pass.
- `:terminal:terminal-app:compileJava` — pass.
- Web TypeScript production build — pass.
- Web unit/component tests — 9 files / 13 tests pass.

The compatibility tests cover the fixture lifecycle, update/rollback/disable, overlay precedence,
stale-fingerprint suppression, all injected active-index crash windows, corrupt package isolation,
archive traversal, identity/content conflicts, missing dependencies, failed evidence, signed-index
tampering, authorization scope escape, native boundary and cross-instance isolation.

## Evidence boundary

The unknown-Mod fixture proves the generic framework only. It is not evidence that an arbitrary
third-party Mod is compatible. Live Hermes and human play are not run in this slice and remain the
explicit post-freeze follow-ups `LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING` and
`HUMAN_PLAYTEST_PENDING`.
