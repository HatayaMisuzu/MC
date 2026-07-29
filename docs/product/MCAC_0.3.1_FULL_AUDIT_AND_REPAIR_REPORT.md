# MCAC 0.3.1 full audit and repair report

Status: Current 0.3.1 closeout report
Updated: 2026-07-29

## Outcome

The implementation candidate is locally verified for the automated 0.3.1 release baseline.
Product readiness remains:

```text
READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST_RC
LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING
HUMAN_PLAYTEST_PENDING
```

This report does not relabel Replay, Fixture, GameTest, dedicated-server automation, or browser
automation as Live-provider or human evidence. The user explicitly deferred Live Hermes and human
playtesting.

## Revision identity

| Field | Value |
|---|---|
| Starting `main` | `ec56aca171f4bc7ffb43eca09cad07678b225163` |
| Working branch | `codex/mcac-0.3.1-audit-repair` (merged) |
| Implementation candidate | `8aa284f3f44c8728658232a3cd01d75acd4ec900` |
| Final repair candidate | `5235fbb18f35c5ad7fff8ef207ff02a540588db9` |
| Postfreeze repair candidate 1 | `c0e2fe0cf17465c1070efdc6097554cf21659f5d` |
| Merge / frozen release SHA | `747c7e8046073d9534eae6ae775645341be4cdcd` |
| PR | [#26](https://github.com/HatayaMisuzu/MC/pull/26), ordinary merge |
| Version | `0.3.1` |
| Annotated baseline tag | `mcac-productization-baseline-0.3.1` |
| GitHub Release | [MCAC 0.3.1](https://github.com/HatayaMisuzu/MC/releases/tag/mcac-productization-baseline-0.3.1) |
| Previous immutable tag | `mcac-productization-baseline-0.3.0` |
| Previous tag target | `ec56aca171f4bc7ffb43eca09cad07678b225163` |

The previous tag was inspected locally and on `origin`; it was not moved, deleted, or overwritten.

## Audit population

The initial tracked-tree inventory classified all 649 tracked files: 643 text files and six binary
assets. It included every tracked Markdown document, Java and TypeScript source, Gradle and Loader
workspace, PowerShell/CMD tool, workflow, lock file, verification metadata file, release input, and
binary release asset. The six binaries were reviewed by source, hash, metadata, and packaging
position rather than decompilation. Ignored audit inventories and raw logs are under
`artifacts/codex-verification/0.3.1-audit/`.

The implementation range contains 21 intentional commits and changes 135 files, with 8,997
insertions and 635 deletions. No destructive history rewrite was performed.

## Fixed findings

### Product truth, documentation, and privacy

- Added `docs/product/PRODUCT_TRUTH.json` and made documentation validation cross-check version,
  readiness, Loader modes, managed port range, pending evidence labels, release-document selection,
  and historical-file markings.
- Converged current documentation on Fabric 1.21.1 and Forge 1.20.1 as Full Runtime Bridge targets,
  NeoForge 1.21.1 as Local Only, and managed Runtime ports 8766–8866.
- Replaced tracked personal instance evidence with a redacted template, expanded privacy scanning,
  and limited the repository data ignore rule so Loader resources cannot be silently excluded.
- Current tracked files and the 0.3.1 package no longer contain the removed personal instance
  evidence. Old commits/tags may still contain historical data; removing it would require separate
  destructive-history authorization.

### Compatibility input safety and supply chain

- Enforced a 16 MiB actual total-inflated-byte budget before document allocation, a single accepted
  YAML manifest, bounded JSON/YAML depth and scalar/token sizes, duplicate-key and trailing-document
  rejection, and disabled YAML aliases.
- Added deterministic tracked-tree and added-diff secret scanning plus pinned Gitleaks CI.
- Enforced npm/Gradle/Action pins, four wrapper checksums, 16 dependency lock files, four dependency
  verification metadata sets, SBOM/package checks, and a Gradle 9 compatibility probe.
- Closed the Fabric/Loom cold-cache checksum gap without disabling verification or accumulating
  retry hashes: 52 exact locally generated coordinates are narrowly trusted, while 47 exact remote
  Fabric API JARs must also match a checked-in timestamp-independent entry-content manifest and the
  pinned Fabric signing certificate before compilation. Static validation rejects `also-trust`,
  group/regex expansion, unexpected trust attributes, and unrelated external artifacts. Independent
  Cold A/Cold B caches matched all 47 canonical hashes, and strict Cold B build/launch preparation
  passed through Loom cache-recovery source remapping.
- Regenerated stable SHA-256 verification metadata through Gradle's official write mode for both
  Forge 1.20.1 and NeoForge 1.21.1 after the first postfreeze heavy run exposed missing
  repository-served Gradle module metadata. Independent fresh-cache read-only
  `createMinecraftArtifacts` runs then passed for both Loader workspaces without writing metadata,
  adding trust rules, disabling verification, or retrying the failed remote SHA.
- Removed project-owned internal/deprecated Gradle API use while preserving documented third-party
  Loader-plugin warning boundaries.

### MCP, local credentials, and filesystem boundaries

- Applied fail-closed current-owner token/state permissions on Windows and POSIX. Sensitive files
  created by MCAC are now atomically created empty, explicitly assigned to the resolved current
  Windows principal, and restricted before any secret or state is written; existing foreign-owned
  files are never taken over. Web bootstrap state remains protected and is removed at shutdown.
- Bound MCP sessions to a domain-separated pairing-token generation so same-token restart recovery
  remains valid while rotation invalidates old sessions without revealing authorization details.
- Added real Windows junction coverage and Linux file/directory symlink coverage across Workspace,
  installer backup/restore, and Compatibility Store boundaries.
- Preserved independent Controller PKI and complete user-to-Companion ACL as explicit future
  architecture work rather than claiming it in 0.3.1.

### Product authority and UX

- Kept `Task Graph Runtime = deterministic orchestration` and `External Brain = reasoning and
  planning`; the retired internal `/agent`, CLI planner, and no-Brain game-chat planning path remain
  closed.
- Added bilingual Minecraft command/help resources and validation that all three Loader artifacts
  package matching keys.
- Documented exact Hermes, OpenAI-compatible, and Replay Adapter differences, the six current
  declarative Skills, and honest acceptance work for route construction, broader survival Skills,
  and the first real third-party Compatibility Pack.

### Phase 8 root-cause repairs

- Replaced Forge scheduler-callback counting with an unchanged real 480-server-tick retreat
  deadline.
- Isolated long Fabric and Forge movement stages from generated-world hazards and adjacent parallel
  fixtures while retaining original distances, health, inventory, visibility, and arrival
  assertions.
- Kept fixture entities inside their declared GameTest authority, crossed real entity-index
  boundaries, discarded owned drops/projectiles/threats, and allocated a fresh unknown-Mod menu
  container for every open.
- Routed all 30 Fabric mock Owners through one cleanup boundary and prevented asynchronous behavior
  chains from declaring terminal GameTest success before the final stage.
- Removed post-registration mock-player UUID mutation. The Fabric mining fixture now uses a
  dedicated forced arena and keeps Owner outside real `ItemEntity` pickup range, preventing Owner
  and Companion from competing for the two declared diamond drops.
- Restored child stdout/stderr before nonzero-exit enforcement so future external-orchestration
  failures retain their first diagnostic evidence.
- Made Windows installer journal replacement write, close, force, and atomically replace a
  same-directory temporary file; only Windows sharing failures receive one bounded primitive-level
  retry window, and every path removes temporary residue without rerunning an install or E2E.

### Postfreeze final-gate root-cause closure

- Candidate `c0e2fe0cf17465c1070efdc6097554cf21659f5d` passed PR fast and the complete
  supply-chain/CodeQL workflow. Its first Windows and Minecraft-heavy runs were preserved as
  failures rather than rerun.
- The Windows first failure showed that an elevated hosted Runner can assign
  `BUILTIN\Administrators` as the default owner of a newly created empty state file before MCAC
  installs its owner-only ACL. The creation API now establishes the individual current-user owner
  before callers write data, while the existing-file path still rejects every foreign owner.
- The heavy first failure showed stable `.module`/parent POM artifacts resolved by the Forge
  toolchain but absent from Loader verification metadata. Official fresh-cache generation added
  the exact SHA-256 entries to both Forge and NeoForge metadata. Separate read-only fresh caches
  pass artifact creation for Forge and NeoForge.
- Focused owner/token/Web tests, the Windows-equivalent prebuilt Terminal/Runtime/Fabric build,
  and the one-worker real-backend Chromium product path pass locally. Exact final-candidate remote
  evidence remains pending and is not inferred from these local results.

These repairs changed fixture ownership, timing authority, process diagnostics, or one atomic
filesystem primitive. They did not weaken assertions, add arbitrary sleeps, delete cases, or
increase full-E2E retry counts.

## Frozen-candidate local gates

All rows below bind to implementation candidate
`8aa284f3f44c8728658232a3cd01d75acd4ec900`.

| Gate | Result | Evidence classification and application marker |
|---|---|---|
| `clean check buildPlatforms` | PASS | Unit/integration/build; shared checks and all three Loader artifacts |
| `launchTest` | PASS | Dedicated-server automation; Fabric, Forge, and NeoForge launched and stopped |
| `gameTest` | PASS | Real Minecraft GameTest; Fabric 31/31, Forge 4/4, NeoForge 1/1 |
| Fabric fixture reconciliation | PASS | 30 Companions created, 30 removed; zero required failures/timeouts |
| `runtimeFabricE2E` | PASS | Dedicated-server automation with authenticated Runtime and Replay external client |
| `runtimeForgeE2E` | PASS | Dedicated-server automation; authenticated follow/pause/resume/cancel |
| `persistenceRestartTest` | PASS | Two-process Fabric restart; body, UUID, and inventory recovered |
| `forgePersistenceRestartTest` | PASS | Two-process Forge restart; interrupted navigation recovered `PAUSED` |
| `runtimeMultiProfileTest` | PASS | Isolated authenticated identities/telemetry; stopping A left B healthy |
| `runtimeDisabledLaunchTest` | PASS | Dedicated-server automation; configured Runtime-disabled path |
| `brainReconnectE2E` | PASS | Deterministic integration test |
| `longPlay100TurnTest` | PASS | Deterministic 100-turn long-run integration test |
| `realPlaySoakTest` | PASS | Automated soak fixture; not human play |
| `verifyTerminalPackage` | PASS | Package, manifest, SBOM, hashes, executables, and version |
| `htmlTerminalStartTest` | PASS | Packaged real backend; arbitrary-directory start and single-instance reuse |
| `releaseGoldenPathTest` | PASS | Browser with real backend; clean extraction and bilingual product path 1/1 |
| `check --warning-mode all` | PASS | Unit/integration/static/documentation/security gates |
| Web `npm ci` | PASS | Exact lock-file install; 238 packages |
| Web production dependency audit | PASS | Zero vulnerabilities at high-or-higher threshold |
| Web lint | PASS | ESLint |
| Web unit tests | PASS | 10 files, 15 tests |
| Web production build | PASS | TypeScript and Vite production build |
| Web E2E | PASS | Browser with real backend; bilingual packaged product path 1/1 |
| `git diff --check` / final status | PASS | No tracked difference on the frozen implementation candidate |

The first `runtimeFabricE2E` process was externally terminated when the Codex turn was intentionally
aborted. Its complete log contains Windows status `0x40010004`, no application assertion failure,
and no surviving test process or listener. It is classified as `EXTERNAL_ENVIRONMENT_FAILURE`, not
a product failure. The single permitted recovery run passed. Both records are retained in the
SHA-bound ignored ledger:
`artifacts/codex-verification/0.3.1-audit/final-gate-ledger.json`.

No complete candidate-chain restart was consumed after the continuation instruction, and the
already-passing build, launch, and GameTest gates were not rerun.

## Candidate release artifacts

The published package was rebuilt from frozen merge SHA
`747c7e8046073d9534eae6ae775645341be4cdcd`; its manifest declares that exact value as
`sourceCommit`.

| Artifact | SHA-256 |
|---|---|
| `mcac-release.zip` | `a7f7a643211a894d0e38442fdae5c571141642a8295efdb7c8ecc87ee4b6361a` |
| `release-manifest.json` | `0ba06bfc2de133e2d0abcbd329dfed581c9aee6bb0fc28a587f3d8bdae3f7f55` |
| `sbom.spdx.json` | `b9a419573c724c9fe0fbb6489ebbc42ef70495afdd19330d22b9126080a3c1a3` |
| `SHA256SUMS.txt` | `5afebfa4b8615d037cbda1d31f065a62cf4b9e9868248d6e914c71fa0d4774b1` |

The GitHub Release contains these four files plus the external `mcac-release.zip.sha256` sidecar.

## Remote and publication state

PR #26 was marked Ready and merged with an ordinary merge commit. At the final repair candidate,
PR Fast run `30436629059` and Supply chain/CodeQL run `30436629064` completed successfully. The
earlier Minecraft-heavy run `30433326289` completed successfully before the later gate-only
PowerShell changes. Windows run `30436629017` was still running when the user explicitly directed
the closeout to stop waiting for project gates and tests; this report does not relabel that run as
successful.

The frozen merge SHA was packaged locally without running another complete verification suite.
Annotated tag `mcac-productization-baseline-0.3.1` targets the merge SHA, and the non-draft,
non-prerelease GitHub Release publishes all five assets.

## Retained limitations

- `LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING`
- `HUMAN_PLAYTEST_PENDING`
- NeoForge 1.21.1 remains Local Only.
- Independent Controller PKI and complete user-to-Companion ACL remain future architecture work.
- General-purpose Script VM remains deferred; the typed declarative Task Graph DSL supplies bounded
  conditions, loops, retries, recovery, persistence, and Skill reuse.
- Active route construction through breaking, placing, bridging, or pillaring is not claimed.
- Fixture unknown-Mod coverage proves generic mechanisms, not universal third-party Mod support.
- Historical personal evidence may remain in old Git objects; no history rewrite was authorized.

## Rollback

The repair branch can be abandoned before merge without changing `main` or either baseline tag.
After merge, use an ordinary revert commit; do not move either annotated baseline tag. Installer
update journals and packaged rollback retain the prior manifest/artifacts, while uninstall offers
the documented preserve-world and full-managed-removal policies.

## Acceptance

- Phases 0–7: completed, tested, committed, and pushed.
- Phase 8 frozen implementation candidate: locally verified.
- Phase 9 PR/ordinary merge/tag/Release: completed under the user's explicit instruction not to
  wait for or rerun project gates.
- Final report: governed with actual candidate, merge, tag, Release, asset and remote-run
  identities.
