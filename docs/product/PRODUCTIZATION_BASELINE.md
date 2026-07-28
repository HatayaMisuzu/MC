# MCAC 0.3.0 automated productization baseline

Status: Superseded / Historical. This file describes the immutable 0.3.0 baseline, not current
0.3.1 status. Current truth: `docs/PRODUCT_STATUS.md` and `docs/RC_COMPLETION_MATRIX.md`.

Prepared: 2026-07-28 (Asia/Shanghai)

This document freezes the automated product boundary required by the productization execution
file. It is not a stable-release claim and does not relabel Replay, Fake, GameTest, browser
automation, or dedicated-server automation as Live-provider or human-play evidence.

## Immutable identity

| Field | Value |
|---|---|
| Product version | `0.3.0` |
| PR | `#3` |
| Exact PR candidate | `48f799e8e880858c81a8516c6d92f128bda24b60` |
| Merge commit / main SHA at baseline preparation | `20b3b2eaa87756884e3d96e67737c99554d5b67e` |
| Frozen final main identity | The commit targeted by annotated tag `mcac-productization-baseline-0.3.0`; the Release `release-manifest.json` `sourceCommit` must equal that tag target |
| Release | `build/distributions/mcac-release.zip` |
| Release SHA | `build/distributions/mcac-release.zip.sha256` |
| Manifest | `build/distributions/mcac-release/release-manifest.json` |
| SBOM | `build/distributions/mcac-release/sbom.spdx.json` |

The ZIP, Manifest, and SBOM hashes are generated from the final tag target after this source
document is committed. Their authoritative values are the external ZIP sidecar, annotated-tag
message, and final closeout delivery. Embedding a ZIP's own hash in a document inside that ZIP
would be self-referential.

## Candidate evidence

All candidate runs below use exact head `48f799e8e880858c81a8516c6d92f128bda24b60`.

| Gate | Run | Result |
|---|---:|---|
| PR fast checks | `30287614857` | PASS |
| Windows terminal/package | `30287614849` | PASS |
| Minecraft heavy | `30287667164` | PASS |
| Supply chain / dependency review / CodeQL | `30287614859` | PASS |
| CodeQL Java | job in `30287614859` | PASS |
| CodeQL JavaScript/TypeScript | job in `30287614859` | PASS |

Exact-main run IDs are recorded in the immutable annotated-tag message and final delivery because
those runs can only exist after the report commit has established the final main SHA.

## Supported product boundary

| Minecraft | Loader | Java | Runtime mode |
|---|---|---:|---|
| 1.21.1 | Fabric | 21 | `FULL_RUNTIME_BRIDGE` |
| 1.20.1 | Forge | 17 | `FULL_RUNTIME_BRIDGE` |
| 1.21.1 | NeoForge | 21 | `LOCAL_ONLY` |

Detection is not a support claim. Unlisted versions, arbitrary third-party Mods, and arbitrary
modpacks remain unverified.

## Compatibility and UI contracts

- Compatibility Pack schema: `mcac-compat/1`.
- Compatibility Host API: version `1`, bounded declaration-only lifecycle surface. Dynamic
  in-process native extension execution is closed.
- Compatibility fixture: isolated unknown-Mod v1/v2 install, evidence, index, activate, update,
  rollback, quarantine, disable, remove, and recovery are automated.
- Modpack overlay fixture: deterministic overlay precedence and stale-fingerprint suppression are
  automated.
- UI languages: `zh-CN`, `en-US`, with persistent independent switching.
- Interaction matrix: `mcac-ui-interaction-matrix/1`, 33 rows; 32 `PASS`, one deliberate `PARTIAL`
  for user-owned account launch/human interaction.
- Risk coverage: 6 critical, 12 high, 7 medium, and 8 low interaction rows.

## Automated acceptance

The frozen scope passed Java unit/integration tests, all supported Loader builds and dedicated
server launches, Fabric/Forge/NeoForge GameTests, Fabric and Forge Runtime E2E, both persistence
restart gates, multi-Profile isolation, Runtime-disabled launch, reconnect recovery,
unknown-Mod generic interaction, 105-turn and 200-turn reliability gates, Web audit/lint/unit
tests/build, real packaged-backend bilingual Chromium flows, clean-package verification,
arbitrary-working-directory HTML startup, and clean-extraction Golden Path.

Package validation verifies every Manifest payload path/size/SHA, every `SHA256SUMS` entry, SPDX
2.3 component metadata, required bilingual/compatibility/legal documents, safe ZIP paths, one
SLF4J provider, and absence of development state, user state, local paths, and secret-shaped
residue.

## Honest pending status and limitations

```text
LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING
HUMAN_PLAYTEST_PENDING
```

See `KNOWN_LIMITATIONS.md`. In particular, Live Hermes authorship, personal-launcher human play,
arbitrary Mod mechanics, production-duration field observation, and a general-purpose script VM
are outside this automated baseline.

## Freeze and hotfix policy

No feature, compatibility, or UI work is added to this tag. Live Hermes and human testing use the
tagged ZIP unchanged. A newly discovered blocker must use a separate `codex/hotfix-*` branch and
PR, repeat proportional local gates and every exact-SHA remote gate, generate a new Release and
hash set, and receive a new annotated baseline tag. The existing tag is never moved or rewritten.
