# MCAC product status

Updated: 2026-07-29

MCAC 0.3.1 is the released repair baseline for the frozen automated productization scope.
Its readiness is `READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST_RC`; this does not constitute Live-provider
or human-play evidence. Machine-readable product facts are in
[PRODUCT_TRUTH.json](product/PRODUCT_TRUTH.json), while exact module evidence is maintained only in
[RC_COMPLETION_MATRIX.md](RC_COMPLETION_MATRIX.md).

## Current support

| Minecraft | Loader | Java | Current mode |
|---|---|---:|---|
| 1.21.1 | Fabric | 21 | `FULL_RUNTIME_BRIDGE` |
| 1.20.1 | Forge | 17 | `FULL_RUNTIME_BRIDGE` |
| 1.21.1 | NeoForge | 21 | `LOCAL_ONLY` |

Other instances may be detected and diagnosed, but MCAC does not claim a Full Runtime Bridge for
them. The Compatibility Host foundation can install, validate, index, activate, update, roll back,
quarantine, disable and remove bounded declaration-only compatibility packs. That foundation and
its fixtures do not establish arbitrary version, Loader, Mod or modpack compatibility.

## Current closure state

- Compatibility Host lifecycle and bilingual Terminal management are locally verified.
- The `zh-CN` and `en-US` critical product paths pass in Chromium against the real packaged Java
  backend and an isolated test launcher instance.
- The 0.3.0 automated baseline is frozen and its tag remains immutable.
- The 0.3.1 repair was ordinarily merged as `747c7e8046073d9534eae6ae775645341be4cdcd`
  and published as annotated tag and GitHub Release `mcac-productization-baseline-0.3.1`.
- Live Hermes and subjective human play are explicitly deferred until after the automated baseline.

The only permitted external follow-up labels are:

```text
LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING
HUMAN_PLAYTEST_PENDING
```

Replay, Fake, Mock, GameTest, dedicated-server automation and browser automation are never relabeled
as Live-provider or human-play evidence.
