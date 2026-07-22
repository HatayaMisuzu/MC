# Known limitations — 0.3.0 RC

The authoritative completion evidence is in `docs/RC_COMPLETION_MATRIX.md`. These limitations are
kept explicit so Replay, local automation, and external verification are not confused.

- Fabric 1.21.1 is the full Runtime Bridge RC target. Forge 1.20.1 and NeoForge 1.21.1 are detected,
  diagnosed, and packaged as `LOCAL_ONLY`; they do not claim the Fabric body/Tool bridge.
- Live external-Brain verification is pending. Automated external-client, Replay, Fake, Mock,
  GameTest, and Dedicated Server evidence is not described as a paid/live model run.
- Human playtesting in a personal launcher account and world is pending. Automated fixtures never
  read or store launcher account credentials.
- OpenAI-compatible Brain and Search providers require user-supplied environment variables. The
  repository and support bundle contain no production API keys, and live provider calls may cost
  money when a user explicitly enables them.
- Unknown Mod content is discovered through live Registry, recipe, Observation, and generic
  interaction primitives. Compatibility with every third-party menu or mechanic is not guaranteed.
- Movement is bounded local body control with stuck detection and safety Reflexes; it is not a
  general-purpose global pathfinding replacement.
- Production-duration multi-profile load and reconnect behavior still require field observation.
- A general-purpose script VM is deliberately deferred; declarative Skills execute only through the
  typed, permission-bound Task Graph Runtime.
