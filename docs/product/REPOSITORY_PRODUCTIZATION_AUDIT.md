# Repository productization audit

Status: Superseded / Historical. This file is not current status. Current truth:
`docs/PRODUCT_STATUS.md` and `docs/RC_COMPLETION_MATRIX.md`.

Updated: 2026-07-27  
Scope: current productization candidate before final clean-tree gates

This audit records concrete findings and fixes. It does not replace
[`docs/RC_COMPLETION_MATRIX.md`](../RC_COMPLETION_MATRIX.md), does not claim Live-provider or human
evidence, and must be updated with exact release results after the final candidate is rebuilt.

## Scanned scope

- production and test Java across Compatibility, Core, protocol, Runtime, Terminal and all Loaders;
- React/TypeScript source, localization, component tests and Playwright;
- Gradle, npm lock data, PowerShell and command launchers;
- GitHub Actions, dependency pinning and secret scanning;
- tracked files, ignored generated/state directories and local absolute-path patterns;
- product, user, developer, execution, compatibility and historical documentation;
- installer, support bundle, release assembly, legal input, Manifest/SBOM/SHA generation.

## Findings and disposition

| ID | Area | Finding | Disposition |
|---|---|---|---|
| DOC-01 | Product status | README still called the dual-Full-Bridge candidate “Fabric-first”; current product/status/user/developer/archive entry pages were absent. | Fixed. README now describes Fabric + Forge Full Bridge and NeoForge `LOCAL_ONLY`; `docs/INDEX.md`, `docs/PRODUCT_STATUS.md`, two user guides, developer entry and archive index were added. |
| DOC-02 | Historical evidence | The chronological External Brain log repeated an old readiness label as current, while the sole RC matrix now says productization closure is in progress. | Fixed. The log is explicitly superseded as a status source; the archive index preserves it and other historical reports without deleting evidence. |
| DOC-03 | Roadmap | The post-productization P0 page said any-version/Mod compatibility was entirely unimplemented after the Compatibility Host foundation had started. | Fixed. It now distinguishes the bounded declaration-only foundation from future broad version/Loader/third-party Mod support. |
| DOC-04 | Documentation gate | Existing link/readiness checks did not require the product status, bilingual guides, developer entry or archive boundary, and did not detect the Fabric-first/Forge-mode contradiction. | Fixed. `documentationCheck` requires the authoritative structure and checks current documents for obsolete readiness, Fabric-first and Forge `LOCAL_ONLY` contradictions and local-machine paths. |
| UI-01 | Real browser | Compatibility API dates failed JSON serialization; `coordinate` and Loader type were missing/misread; the update action and complete lifecycle lacked browser proof. | Fixed and locally verified. Java Time serialization, explicit coordinate properties, Loader field, update action and the isolated v1/v2 lifecycle pass against the real packaged Java backend in both locales. |
| CI-01 | Windows portability | The E2E fixture builder used `Get-FileHash`, which was unavailable in the hosted PowerShell 7 job and stopped Windows CI before Chromium launched. | Fixed with a bounded .NET SHA-256 helper that disposes the algorithm and stream. The same E2E passes locally after the change; exact-SHA remote confirmation remains required. |
| PROTO-01 | Behavior lifecycle | Loaders report a safely blocked behavior with `BLOCKED` plus a machine-readable failure code, but the protocol model allowed failure details only on `FAILED`; Runtime rejected a legitimate blocked observation and emitted `SEVERE`. | Fixed. `BLOCKED` now requires the same bounded failure code/message detail while non-failure states still reject it. Protocol tests cover accepted/missing details and the full unknown-Mod Runtime/Fabric E2E passes without unexpected severe logs. |
| CLEAN-01 | Generated/state files | Build, Gradle, Web dist, Playwright result and screenshot directories exist locally. | Correctly ignored. No build/output/test-result/log/database/cache/archive artifact is tracked. They are rebuilt for gates and excluded from source/release unless explicitly assembled. |
| TEST-01 | Skips | One test uses `Assumptions.abort` when the host cannot create symbolic links. | Retained with explicit reason. The security behavior is tested on capable hosts; this is not an unlabelled or product-path skip. No `@Disabled`, Playwright skip or workflow `continue-on-error` was found. |
| TEST-02 | Restart fixture | Fabric persistence seed used a finite 150-block `GOTO`; the accelerated GameTest clock could complete it before shutdown and save truthful `IDLE`, invalidating the intended in-flight premise. | Fixed by seeding continuous FOLLOW navigation after moving the owner. The two-process gate again proves the same UUID/body/inventory and restart quarantine to `PAUSED` without extending a timeout. |
| TEST-03 | Launch fixture inputs | `prepareLaunchTest` declared its directory output but not the Runtime-disabled or persistence-probe mode inputs, allowing Gradle to reuse a normal-launch fixture for the disabled test. | Fixed by declaring both properties as task inputs. The disabled bridge test now writes the correct config and proves there is no token lookup. |
| DEBUG-01 | Console output | CLI entry points and fault-injection helpers write structured progress to stdout. | Retained. These are user/automation interfaces, not forgotten debug statements; no browser `console.log`, debugger statement, `printStackTrace`, TODO, FIXME or XXX was found in reachable product code. |
| LEGAL-01 | Legal package | The project license existed, but there was no reader-facing NOTICE in the release assembly. | Fixed. `NOTICE` identifies MCAC terms, routes exact third-party inventory to the SPDX SBOM/provenance file and adds a non-affiliation statement; release assembly includes it under `legal/`. |
| DEP-01 | Dependencies | Dynamic top-level dependency or mutable GitHub Action references could make the candidate non-reproducible. | No defect found. `dependencyPinningCheck` passed; npm production audit reported 0 vulnerabilities. Transitive npm ranges remain lockfile-resolved and are not treated as floating roots. |
| SEC-01 | Secrets and paths | Source, docs or support surfaces could contain key-shaped values or developer-machine paths. | No defect found. `secretCheck` passed; current-doc path checks passed. Test-only adversarial secret/path strings remain intentionally present to verify redaction. |

## Verification performed for this audit

- `documentationCheck`: passed for 45 Markdown files, internal links, required paths, documented
  commands and readiness labels.
- `secretCheck`: passed.
- `dependencyPinningCheck`: passed.
- `npm audit --omit=dev --audit-level=high`: 0 vulnerabilities.
- `git diff --check`: passed.
- tracked generated/state file scan: none.
- bilingual packaged-backend Playwright path after the PowerShell portability fix: 1 passed.
- Fabric and Forge two-process persistence restart gates: passed.
- Runtime-disabled launch and authenticated multi-Profile isolation gates: passed.
- Brain reconnect, unknown-Mod generic Runtime/Fabric E2E, 105-turn reliability and 200-turn
  bounded local soak gates: passed when run as isolated gates.

## Known limitations retained

- Live Hermes remains `LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING`.
- Human play remains `HUMAN_PLAYTEST_PENDING`.
- NeoForge 1.21.1 remains `LOCAL_ONLY`.
- Compatibility fixtures prove the Host contract and lifecycle, not arbitrary third-party Mod,
  version or modpack compatibility.
- Dynamic native compatibility extension execution remains closed.
- The general-purpose script VM remains deferred behind the typed declarative Task Graph boundary.
- Production-duration field observation and broader compatibility-pack corpus remain future work.

## Release result

The release assembly now includes the current product status, compatibility, bilingual user,
developer and legal documents. Exact candidate Manifest/SBOM/SHA and clean-extraction results are
pending the final clean-tree release gate; they will be recorded here without reusing an older SHA.
