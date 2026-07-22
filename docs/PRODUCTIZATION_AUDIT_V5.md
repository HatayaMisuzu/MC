# Productization closure v5 audit

Updated: 2026-07-22
Audited source baseline: `1a3fab4a022fbd5380041e992e7baf82bd8d4581`
Execution contract: `MCAC_CODEX_PRODUCTIZATION_CLOSURE_v5.md` (external execution contract)

This is the required pre-change audit table for the v5 closure pass. It is evidence and work
tracking, not a second completion matrix. `docs/RC_COMPLETION_MATRIX.md` remains the only authority
for current implementation status. Replay, Fake, Mock, fixture, deterministic external-client,
GameTest, and local automation evidence are not Live-provider or human-play evidence.

| ID | Area | Classification | Evidence at audit baseline | Required closure action |
|---|---|---|---|---|
| BASE-001 | Git baseline | REQUIRED_CLEANUP | Clean `main` was fast-forwarded to actual `origin/main` at `1a3fab4`; the nine commits after the contract's known baseline were inspected before new work | Preserve the clean history and bind the final matrix/package/CI evidence to the eventual final SHA |
| DOC-001 | RC matrix | REQUIRED_CLEANUP | `docs/RC_COMPLETION_MATRIX.md` still names `3cc7861` as its Baseline although current implementation and evidence extend through `1a3fab4` | Update only after local gates pass, using an exact source/evidence SHA and without upgrading unfinished rows |
| DOC-002 | External Brain state | BUG_OR_SECURITY | `docs/EXTERNAL_BRAIN_STATE.md` says the release is not RC-ready and that major streams remain incomplete, contradicting the authoritative matrix and later sections in the same chronological log | Mark the statement historical and point current status exclusively to the matrix |
| DOC-003 | Post-closure roadmap | REQUIRED_CLEANUP | The v5-required `docs/POST_PRODUCTIZATION_P0.md` is absent | Add the three equal post-closure P0s without implementing them or expanding this RC's scope |
| DOC-004 | Evidence labels | VALID_KNOWN_LIMITATION | Current docs distinguish deterministic `liveModel=false`, GameTest, Live provider, and human play; Live Brain and human play remain pending | Preserve these labels in all regenerated evidence and final reporting |
| CODE-001 | Incomplete-marker scan | REQUIRED_CLEANUP | Production scan found nullable lookup/parse returns, bounded compatibility fallbacks, ignored optional-discovery errors, atomic-move fallbacks, and default unsupported provider resume; no scenario Handler, arbitrary script VM, or obvious production fixture namespace special-case was identified by the lexical scan | Review focused hits against tests; fix only behavior that can hide a product failure, security issue, or declared support gap |
| SEC-001 | Runtime authority/privacy | REQUIRED_CLEANUP | Source exposes bounded HTTP/WebSocket/provider/file operations and contains explicit token, CSRF, loopback, scope, redaction, and Workspace checks; broad lexical matches require validation rather than being accepted as proof | Run the repository security/privacy tests and inspect any failure; do not grant Runtime Brain shell, arbitrary filesystem, credentials, or unrestricted network access |
| TEST-001 | Local gates | REQUIRED_CLEANUP | The repository has all required component gates, but no aggregate `minecraftHeavy` task; the actual remote workflow runs `buildPlatforms`, `launchTest`, `gameTest`, `runtimeFabricE2E`, `persistenceRestartTest`, and `runtimeMultiProfileTest` | Run the exact component gates on the audited/final source; classify skipped link tests honestly and investigate any flaky failure instead of rerunning to luck |
| REL-001 | Release package | REQUIRED_CLEANUP | Existing generated release files may predate the final source even when structurally valid | Rebuild from the final commit, verify ZIP layout, manifest, SBOM, SHA256SUMS, sidecar, and embedded source SHA |
| REL-002 | Packaged logging | BUG_OR_SECURITY | The first v5 Golden Path exposed both `slf4j-nop` and `slf4j-simple` in the merged jpackage classpath; SLF4J selected NOP, silently disabling the intended Runtime diagnostic provider | Align both launchers on `slf4j-simple` and make package verification reject zero or multiple providers |
| CI-001 | Remote CI | REQUIRED_CLEANUP | At `1a3fab4`, PR fast run `29894124176`, Windows run `29894124114`, and Minecraft heavy run `29894124139` are green | After any closure commit, require the same three workflows green on that exact new SHA before claiming completion |
| EXT-001 | Live Brain/human | VALID_KNOWN_LIMITATION | No paid/live provider credential or human play evidence is supplied by this execution contract | Retain only `LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING` and `HUMAN_PLAYTEST_PENDING`; never relabel automation as either |
| P0-POST | Deferred product directions | POST_CLOSURE_P0 | Sub-Agent delegation, any-version/Loader Bridge, and true arbitrary-Mod compatibility are explicitly outside this closure | Document as equal post-productization P0s and do not implement in this pass |

## Audit decision

No known P0/P1 product defect was established by the document/source scan or local gates. The
documentation contradictions, missing roadmap, and packaged logging-provider conflict were repaired.
Non-cached base tests, Loader builds/launches/GameTests, Runtime/Fabric and unknown-Mod E2E,
persistence/restart, multi-profile, Brain/Capsule/Memory/long-play/soak, Web, package verification,
arbitrary-working-directory launch, and clean-extraction Golden Path passed locally. The v5 closure
commit `2bdb030d65425c20113ab260eeacfa7fdb9f72d9` was rebuilt into the verified
release and passed PR fast run `29896989946`, Windows run `29896989922`, and Minecraft heavy run
`29896989911`. During the v5.1 final gate, accelerated GameTest ticks exposed that a documented
60-second menu capability was actually tick-bound; it now uses a monotonic 60-second wall-clock
lifetime, still invalidates on menu replacement/close, and has boundary assertions plus repeated
Runtime/Fabric E2E coverage. Live-provider and human-play verification remain the only external
follow-up. The same final gate also exposed an asynchronous Task Graph terminal-feedback race:
`await()` could observe the durable terminal row before the separate lifecycle event transaction.
Awaiters now receive a terminal result only after the idempotent lifecycle event is durable; the
previously failing boundary assertion passed five repeated non-cached targeted runs. Repeated
Runtime/Fabric runs also exposed a harness race between companion registration and asynchronous
connected-body capability publication; the external client now waits, with a fixed bound, for its
declared Tool set through authenticated MCP `tools/list` before submitting the Graph. Runtime Tool
availability validation remains unchanged and strict.
