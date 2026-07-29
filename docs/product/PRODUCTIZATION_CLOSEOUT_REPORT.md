# MCAC 0.3.0 productization closeout report

Status: Superseded / Historical. This file is not the 0.3.1 audit report. Current truth:
`docs/PRODUCT_STATUS.md` and `docs/RC_COMPLETION_MATRIX.md`.

Prepared: 2026-07-28 (Asia/Shanghai)

## Outcome

PR `#3` was recovered from its real head, completed without replaying finished work, validated at
candidate SHA `48f799e8e880858c81a8516c6d92f128bda24b60`, marked ready, and merged with ordinary merge
commit `20b3b2eaa87756884e3d96e67737c99554d5b67e`.

The final frozen main SHA is the target of annotated tag
`mcac-productization-baseline-0.3.0`. Its generated Release Manifest must name that exact target as
`sourceCommit`; its ZIP, Manifest, and SPDX 2.3 SBOM hashes are recorded in the external sidecar,
tag annotation, and final delivery.

## Reconstructed checklist acceptance

| Order | Acceptance item | Result |
|---:|---|---|
| 1 | Recover PR/branch and preserve completed work | PASS |
| 2 | Productization safety/reliability rescan | PASS |
| 3 | Compatibility Host foundation | PASS |
| 4 | Complete `zh-CN` / `en-US` Terminal localization | PASS |
| 5 | Windows browser locale regression | FIXED / PASS |
| 6 | Compatibility API Java Time serialization | FIXED / PASS |
| 7 | Real packaged-backend button lifecycle | PASS |
| 8 | 33-row interaction matrix and bilingual visual review | PASS |
| 9 | Sole RC matrix aligned | PASS |
| 10 | Documentation single source of truth | PASS |
| 11 | Repository/security/dependency/release cleanup | PASS |
| 12 | Full local candidate gates | PASS |
| 13 | Exact candidate Release/Manifest/SBOM/SHA | PASS |
| 14 | Exact candidate remote gates | PASS |
| 15 | PR ready, reviewed, ordinary merge to main | PASS |
| 16 | Exact-main Release, CI, baseline and immutable tag | PASS; final tag identity and hashes are recorded by the tag and final delivery |

## Delivered scope

- Typed primitive Tools and declarative Task Graph/Skill execution remain subordinate to the
  configured external Brain; Runtime has no competing high-level planner.
- The Compatibility Host implements bounded pack loading, immutable Store, signed index,
  transaction journal/recovery, environment fingerprinting, deterministic resolution/composition,
  scoped grants, trace, quarantine, versioning, rollback, and lifecycle operations.
- Fabric 1.21.1 and Forge 1.20.1 expose Full Runtime Bridge support. NeoForge 1.21.1 remains
  honestly `LOCAL_ONLY`.
- The HTML Terminal provides persistent `zh-CN` and `en-US`, visible capability provenance, and
  lifecycle controls through the existing confirmation/progress/result boundary.
- Documentation navigation, bilingual user guides, terminology, limitations, current product
  status, historical-document governance, release integrity, legal notices, and CI security gates
  are aligned with the sole completion matrix.

## Compatibility and UI evidence

```text
COMPAT_SCHEMA_VERSION=mcac-compat/1
COMPAT_HOST_API_VERSION=1_DECLARATION_ONLY
COMPAT_FIXTURE_LIFECYCLE=PASS
MODPACK_OVERLAY_LIFECYCLE=PASS
UI_LANGUAGES=zh-CN,en-US
UI_INTERACTION_MATRIX=mcac-ui-interaction-matrix/1 (33 rows)
UI_CRITICAL_BUTTONS=6/6 PASS
UI_HIGH_FREQUENCY_BUTTONS=12/12 PASS
UI_EXCLUSIONS=1 PARTIAL; user-owned account launch and subjective human play only
```

The real-backend Chromium test uses an isolated launcher fixture and packaged Java
Terminal/Runtime; it does not mock `fetch`. GameTest is real Minecraft execution but not human
play.

## Candidate remote runs

```text
REMOTE_FAST=30287614857 PASS
REMOTE_WINDOWS=30287614849 PASS
REMOTE_MINECRAFT_HEAVY=30287667164 PASS
REMOTE_SUPPLY_CHAIN=30287614859 PASS
REMOTE_CODEQL_JAVA=30287614859 PASS
REMOTE_CODEQL_JS=30287614859 PASS
```

Post-merge exact-main run IDs are intentionally external to this commit and are placed in the
immutable annotated-tag message and final delivery.

The pre-freeze exact-main checkpoint `cb5ddfee5773d74cf4fa970eb29221d6d0223e77`
passed fast `30290340793`, Windows `30290340461`, Minecraft heavy `30290340900`, and
supply-chain/CodeQL `30290341011`. The final documentation-only tag target is independently
rechecked, and its run IDs are recorded in the tag annotation and final delivery.

## Limitations and follow-up

```text
LIVE_HERMES_EXTERNAL_VERIFICATION=PENDING_POST_FREEZE
HUMAN_PLAYTEST=PENDING_POST_FREEZE
```

No Live Hermes or human test was executed or fabricated during closure. Both follow-ups must use
the frozen tagged ZIP. Other limitations remain in `KNOWN_LIMITATIONS.md`.

If a follow-up finds a blocker, create an isolated hotfix branch and PR, preserve the existing tag,
repeat the complete affected local and exact-SHA remote gates, rebuild all integrity artifacts, and
create a new annotated baseline tag. No rollback occurred during this closeout; one timed-out
duplicate local test process was terminated by exact repository command line before the affected
gate was rerun cleanly. No user action was required.
