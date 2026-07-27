# UI interaction matrix

The machine-readable source is
[`UI_INTERACTION_MATRIX.json`](UI_INTERACTION_MATRIX.json). This document explains the evidence
rules and the current automated product boundary; it does not upgrade a component or fixture test
to Live-provider or human-play evidence.

## Test environment

- `BROWSER_WITH_REAL_BACKEND` uses Chromium against the real Java Terminal and Runtime distributions
  with an isolated temporary PCL2 instance. No `fetch` mock is used.
- `CLEAN_RELEASE_BROWSER` runs the same browser flow from an extracted release ZIP through
  `releaseGoldenPathTest`.
- `REAL_MINECRAFT_AUTOMATION` means a real dedicated Minecraft/GameTest process. It is not human
  play and not Live Hermes.
- `USER_ACTION_REQUIRED` is limited to launcher account login, the final account-bound game launch,
  and later subjective human-play checks. Its plan, preflight, error and cancellation boundaries
  remain automated.

## Current real-backend browser proof

The bilingual packaged-backend path performs and verifies:

1. every navigation route, locale persistence, ARIA label change, theme state, instance selection
   and refresh;
2. Compatibility Host fingerprint serialization, exact 64-hex digest, built-in capability source
   view, and absence of leaked `undefined` values;
3. isolated v1/v2 compatibility-pack install, Fixture evidence, index, activation, update,
   rollback, quarantine, disable and removal through the visible buttons;
4. install, update, repair, rollback to a real pre-install snapshot, and recovery by reinstall;
5. Runtime start, authenticated identity, live PID/ports, token rotation, restart, stop and closed
   ports;
6. session attach with truthful `SAFE_IDLE` when no game is connected;
7. Runtime log selection and a redacted support-bundle operation;
8. preserve-data uninstall and delete-data uninstall with exact profile-state assertions;
9. two-step Terminal backend shutdown.

The complete critical path runs once in `zh-CN` and once in `en-US`. Screenshots are generated under
`output/playwright/compatibility-zh-CN.png` and
`output/playwright/compatibility-en-US.png` during local verification. Test artifacts are not
packaged into the release.

## Exclusions

| Interaction | Test level | Exact reason |
|---|---|---|
| PCL2/HMCL account login and final account-bound launch click | `USER_ACTION_REQUIRED` | Credentials, account UI and launcher sessions are user-owned and must not enter automation. All MCAC preflight, plan, timeout and cancellation behavior remains automated. |
| Live Hermes decisions | Pending after freeze | The user explicitly deferred dual-loader Live Hermes. Replay and deterministic provider fixtures are not relabeled as Live evidence. |
| Subjective movement feel and ordinary-player usability | Pending after freeze | The user explicitly deferred human playtesting. GameTest remains real Minecraft automation, not a human-play claim. |

## Coverage interpretation

The JSON contains every executable control or cohesive protocol-control family, both language
labels, risk, backend boundary, expected state change, test level, result and a non-empty reason for
every exclusion. Protocol command names such as `follow`, `goto`, `JSON`, `MCP`, Loader names and
technical IDs intentionally remain English under both locales according to
[`TERMINOLOGY.md`](TERMINOLOGY.md).
