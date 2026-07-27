# MCAC product status

Updated: 2026-07-27

MCAC 0.3.0 has reached the automated productization baseline. Exact module evidence is maintained
only in
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
- Repository documentation, cleanup, full candidate gates, exact candidate release integrity,
  remote CI, and the ordinary merge to `main` are complete.
- The exact-main Release integrity and remote gates passed before the final documentation-only
  closure commit; that commit is accepted only after its own exact-SHA gates and immutable
  annotated baseline tag are complete.
- Live Hermes and subjective human play are explicitly deferred until after the automated baseline.

The only permitted external follow-up labels are:

```text
LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING
HUMAN_PLAYTEST_PENDING
```

Replay, Fake, Mock, GameTest, dedicated-server automation and browser automation are never relabeled
as Live-provider or human-play evidence.
