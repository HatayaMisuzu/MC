# Known limitations — 0.3.1

The authoritative completion evidence is in `docs/RC_COMPLETION_MATRIX.md`. These limitations are
kept explicit so Replay, local automation, and external verification are not confused. The
automated baseline is frozen and published as `mcac-productization-baseline-0.3.1`; the readiness
label `READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST_RC` only means the automated closure is complete and
Live Brain / human-play evidence is still pending.

- Fabric 1.21.1 and Forge 1.20.1 are the full Runtime Bridge RC targets. NeoForge 1.21.1 is detected,
  diagnosed, and packaged as `LOCAL_ONLY`; it does not claim the full body/Tool bridge.
- Live external-Brain verification is pending. Automated external-client, Replay, Fake, Mock,
  GameTest, and Dedicated Server evidence is not described as a paid/live model run.
- Human playtesting in a personal launcher account and world is pending. Automated fixtures never
  read or store launcher account credentials.
- OpenAI-compatible Brain and Search providers require user-supplied environment variables. The
  repository and support bundle contain no production API keys, and live provider calls may cost
  money when a user explicitly enables them.
- Natural-language control has no internal Agent fallback. If the independently configured Hermes
  or OpenAI-compatible Brain is disabled, unreachable, credential-missing, or protocol-incompatible,
  requests fail closed. A plain text completion endpoint is not reported as Brain-compatible.
- Action-start receipts are not completion evidence. They carry `completionVerified=false`; callers
  must inspect the durable Task/Task Graph (using the receipt's `taskId` when supplied) and use
  matching connected-body observations before reporting an outcome.
- Compatibility Packs remain `STAGING` until trusted automation supplies real evidence. The Web
  Terminal cannot manufacture `passed=true` or `EXACT_VERIFIED`; broader real-pack and third-party
  Mod evidence remains limited to the concrete Fixture/GameTest/E2E entries in the RC matrix.
- Unknown Mod content is discovered through live Registry, recipe, Observation, and generic
  interaction primitives. Compatibility with every third-party menu or mechanic is not guaranteed.
- Navigation is bounded, deterministic body control with re-planning, stuck detection, unloaded
  chunk boundaries, and safety costs. It does not break or place blocks to invent a route.
- Production-duration multi-profile load and reconnect behavior still require field observation.
- A general-purpose script VM is deliberately deferred; declarative Skills execute only through the
  typed, permission-bound Task Graph Runtime.
