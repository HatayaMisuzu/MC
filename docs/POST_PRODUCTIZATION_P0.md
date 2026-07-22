# Post-productization P0 roadmap

Updated: 2026-07-22

This roadmap starts only after the Fabric 1.21.1 productization closure. The three items below are
equal-priority P0 directions; none is implemented or claimed by the current RC.

## P0-A: Sub-Agent delegation

Hermes or the configured external Brain remains the single companion personality, user interface,
goal owner, and final decision-maker. Future sub-Agents are bounded workers for research, planning,
execution, or verification. The design must include parent-session and delegation IDs, scoped
context, Tool allowlists, read/plan/execute permissions, budgets, expiry and revocation, one mutation
owner, Evidence return, and a prohibition on recursive uncontrolled delegation or direct Memory
approval.

## P0-B: Any-version and Loader Bridge

The future Bridge should detect instance version and Loader, keep the Runtime protocol
version-independent, negotiate capabilities, and use thin isolated version shims with conformance
tests. Shim generation, compilation, installation, and rollback must be user-approved and isolated.
Unsupported combinations must remain honestly `LOCAL_ONLY`; the Runtime Brain must never receive
shell, compiler, source-tree, or unrestricted filesystem access.

## P0-C: True arbitrary-Mod compatibility

The future path combines static instance/Mod analysis with live Registry, recipe, tag, component,
menu, and generic-interaction observations; bounded manuals or authorized search; capability-gap
discovery; and promotion of reusable generic abstractions. It may classify an unknown mechanism and
return unsupported. It must not add one production Handler per ordinary Mod, claim one fixture proves
universal support, or let a Runtime Brain inject code.

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
