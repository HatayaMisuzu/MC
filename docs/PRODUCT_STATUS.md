# MCAC product status

Updated: 2026-08-10

MCAC 0.3.1 is the released repair baseline for the frozen automated productization scope.
Its current readiness is `HUMAN_PLAYTEST_PENDING`. One bounded live-provider vertical slice is now
verified; this is not a claim that every provider, Loader, Tool, terrain, or long-duration human-play
scenario has passed. Machine-readable frozen-release facts are in
[PRODUCT_TRUTH.json](product/PRODUCT_TRUTH.json). Post-release `main` does not inherit that release's
exact-SHA verification; its stable scope and SHA authorities are in
[CURRENT_MAIN_TRUTH.json](product/CURRENT_MAIN_TRUTH.json), while exact module evidence is maintained only in
[RC_COMPLETION_MATRIX.md](RC_COMPLETION_MATRIX.md).

"Released" here means the frozen automated baseline was published as the annotated tag and GitHub
Release `mcac-productization-baseline-0.3.1`. The frozen release remains immutable; current `main`
contains post-release reliability work and evidence updates.

On 2026-08-01, a disposable PCL + Forge 1.20.1 instance completed a real Runtime + Hermes + official
DeepSeek API vertical slice. Sanitized evidence verifies handshake, recovery, world-state reading,
Follow lifecycle, Navigate position change, safe idle, and reconnect. It does not validate Fabric,
other providers, every Tool, complex terrain, or sustained subjective human play.

## Current support

| Minecraft | Loader | Java | Current mode |
|---|---|---:|---|
| 1.21.1 | Fabric | 21 | `FULL_RUNTIME_BRIDGE` |
| 1.20.1 | Forge | 17 | `FULL_RUNTIME_BRIDGE` |
| 1.21.1 | NeoForge | 21 | `LOCAL_ONLY` |

Other instances may be detected and diagnosed, but MCAC does not claim a Full Runtime Bridge for
them. The Compatibility Host foundation can install, validate, index, activate, update, roll back,
quarantine, disable and remove bounded declaration-only compatibility packs. That foundation and
its fixtures do not establish arbitrary version, Loader, Mod or modpack compatibility. The Web
Terminal cannot author `EXACT_VERIFIED` evidence; installed field packs remain `STAGING` until a
trusted test runner supplies evidence through the scoped Host boundary.

## Current closure state

- Current `main` is a post-release development line. Its exact-SHA validation comes only from Git
  and Actions runs for that SHA; it does not inherit the frozen release's remote evidence.
- Compatibility Host lifecycle and bilingual Terminal management are locally verified.
- The `zh-CN` and `en-US` critical product paths pass in Chromium against the real packaged Java
  backend and an isolated test launcher instance.
- The 0.3.0 automated baseline is frozen and its tag remains immutable.
- The 0.3.1 repair was ordinarily merged as `747c7e8046073d9534eae6ae775645341be4cdcd`
  and published as annotated tag and GitHub Release `mcac-productization-baseline-0.3.1`.
- The exact Forge/PCL/Hermes/official-DeepSeek vertical slice above is live-provider verified.
- Broader provider/Loader coverage and subjective long-duration human play remain follow-up work.

The remaining external follow-up label is:

```text
HUMAN_PLAYTEST_PENDING
```

Replay, Fake, Mock, GameTest, dedicated-server automation and browser automation are never relabeled
as Live-provider or human-play evidence.
