# Post-productization P0 roadmap

Updated: 2026-07-22

This roadmap starts only after the dual-Full-Bridge automated productization baseline is frozen.
It is a future roadmap, not a current status source. The Compatibility Host has begun as a bounded
declaration-only lifecycle and management foundation; that foundation does not mean arbitrary
versions, Loaders or third-party Mods are supported.

## P0-A: Sub-Agent delegation

Hermes or the configured external Brain remains the single companion personality, user interface,
goal owner, and final decision-maker. Future sub-Agents are bounded workers for research, planning,
execution, or verification. The design must include parent-session and delegation IDs, scoped
context, Tool allowlists, read/plan/execute permissions, budgets, expiry and revocation, one mutation
owner, Evidence return, and a prohibition on recursive uncontrolled delegation or direct Memory
approval.

## P0-B: Broader version and Loader Bridge

Build on the current Compatibility Host and exact instance fingerprinting to add further version
and Loader implementations. The Runtime protocol remains version-independent; thin isolated
version shims require conformance tests. Unsupported combinations remain honestly `LOCAL_ONLY`.
Runtime Brains never receive shell, compiler, source-tree or unrestricted filesystem access.

## P0-C: Broader third-party Mod compatibility

Extend the current Registry/generic-interaction and declaration-only pack foundations with a field
pack corpus, stronger conformance evidence and, only after a separately reviewed security design,
bounded native extension isolation. Unknown mechanisms may remain unsupported. One fixture never
proves universal compatibility, and Runtime Brains cannot inject code.

## Activation gate

Implementation of these directions begins only when all of the following are true:

```text
PRODUCTIZATION_CLOSURE=COMPLETE
FINAL_REMOTE_CI=PASS
FINAL_RELEASE_PACKAGE=VERIFIED
LIVE_BRAIN_EXTERNAL_VERIFICATION=<completed or explicitly scheduled>
HUMAN_PLAYTEST=<completed or explicitly scheduled>
```

If Live Brain or human testing reveals a blocker in the current RC, that RC is repaired before any
of these P0 directions starts.
