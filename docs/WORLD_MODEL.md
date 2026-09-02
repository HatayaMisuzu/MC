# Bounded local World Model

Feature 7 maps existing facts into one per-companion projection, persisted with the companion's
latest status. It is not a client world cache or a planner.

| Existing fact source | Model entries |
| --- | --- |
| Connected Body runtime snapshot | Current position, dimension, vitals, equipment, inventory, menu, vehicle, behavior |
| Primitive entity/block observation and active stable target binding | Nearby players, relevant entities, threats, drops, target blocks and interactable targets |
| Existing visible-container snapshot | Nearby containers and recently known container locations |
| Navigation route execution snapshot | Current bounded route progress and recent path |
| Runtime task and TaskGraph lifecycle | Current task/graph and task-related locations (intent, not observed world facts) |
| Body observation candidates | Discovered task resources and recent target positions |
| Existing local-user-approved WORLD memory | Explicit landmarks, home/base; no automatic home inference |

## Bounds and freshness

The Runtime owns `_worldModel` in the existing persisted companion status. Body-supplied values
of this reserved field are discarded. A single repository monitor serializes snapshot, query,
event and task updates. No event log, world copy, new planner or extra runtime authority is added.

- At most 16 current, 48 nearby and 64 memory entries per companion. Evict stale/old entries first.
- Current facts expire after 15 seconds; nearby facts after 10 seconds; recent locations/resources
  after 10 minutes and paths after 2 minutes. Reads never renew observation time.
- Each value is bounded by depth, nodes and characters (2,048; inventory 4,096). Partial values
  carry `_truncated` and cannot be returned as a complete verified observation.
- Entity identity is the existing canonical UUID; block identity is dimension plus coordinates,
  within the owning world/companion. A world change clears the prior projection.
- Every entry has identity, source, observedAt, dimension, verified, ttlMillis, stale and
  invalidation. Summary entries also distinguish current state from historical memory.
- A newer snapshot can refresh a stale fact. Older/repeated snapshots or delayed events cannot
  overwrite newer observations. Missing/future timestamps and dormant Body default coordinates
  cannot become verified current facts.
- Dimension changes, death, disconnect/restart and relevant entity/block/container events
  invalidate previous observations. Absence from a bounded visible sample means `NOT_OBSERVED`,
  not proof of death or destruction. Dropping a target binding does not invalidate an entity
  that is still visibly present.

The Body status publisher reuses primitive observation serializers and stable active-target
bindings. Nearby entity observation stays within 16 blocks and 32 entries. Containers use the
existing small visibility boundary (at most 16). Historical resource candidates are reobserved
before publication. Navigation exports only progress, goal and the next eight route steps.
Actual movement continues to use the existing live collision/navigation executors.

`world.query` accepts `model`/`all`, `nearby`, `containers`, and individual current selectors
(`position`, `dimension`, `vitals`, `equipment`, `inventory`, `menu`, `vehicle`, `behavior`,
`navigation`). Stale/incomplete individual observations fail explicitly. `inventory.inspect`
and `safety.inspect` use the same freshness rules. `world.locate_known_container` reads this
model in production; legacy auto-saved container history is no longer injected into Brain context.
Successful authenticated `block.inspect`, `entity.inspect` and `menu.inspect` results also update
this projection. TaskGraph reads it through the ordinary bounded gateway and publishes lifecycle
state through the existing Runtime event listener; task destinations are labeled intent.

## Explicit locations and Brain context

Home/base and landmarks use the existing local-user WORLD-memory creation/edit/approval paths.
They require explicit user provenance and coordinates; an inference or automatic observation
does not define home. New declarations bind to the current companion world, and only matching
world declarations enter context. Old unscoped declarations need an explicit user edit/approval.
Deleting a declaration removes it from the next summary; it is not copied into an independent cache.
User declarations remain labeled `userDeclared`, `current=false`, `verified=false`: the declared
meaning of a place does not prove its current physical condition. Their existing expiry applies;
`ttlMillis=-1` means a non-expiring user declaration.

Brain context includes at most 32 World Model entries and 12,000 serialized characters, with
omission counts. Further total-context clipping drops whole model entries, retaining freshness
metadata on every retained entry. The complete serialized Brain context has a 40,000-character
hard limit. It never receives the persisted model or an entire world cache.

## Feature 7 audit and verification

This is the single system audit for execution file 09, starting at
`434044cecdc0d870b4ce3d43545e96201097f6fb`. It covers only Feature 7.

| Audit boundary | Result |
| --- | --- |
| Required current/nearby/memory facts | Mapped to existing Body, primitive, Navigation, Runtime task/graph and explicit user-memory sources above. |
| TTL, stale, invalidation, identity | Persisted invalidations, read-time expiry, canonical UUIDs, world/dimension separation, and guarded refresh; historical/partial facts cannot masquerade as current verified state. |
| Failure/restart/cleanup | Unavailable Body and stale/incomplete queries fail honestly; reconnect needs new observations; persisted recent memory remains bounded and stale after restart. No world mutations are replayed by this model. |
| Context bounds | Count/value/serialized limits; audit fixed string-clipping provenance and preserved whole entry envelopes under total-context pressure. |
| Target changes | Explicit target invalidation, plus regression coverage for a target that becomes unbound while the same entity remains visible. |
| Permissions | No shell, network, filesystem, world mutation or high-level planning authority added. |
| Dual version | Shared Runtime semantics; 1.20.1 uses its existing Gson tree facade (small Map serializer addition), while 1.21.1 retains Jackson. |

Verification runs serially using trusted cached Gradle 9.5.1 and JDKs with `--offline --no-parallel`.
The restricted sandbox cannot resolve the cached settings plugin; using the existing external
cache resolved that environment issue without changing project dependencies.

- UNIT / INTEGRATION: model expiry, refresh ordering, capacity, malformed/partial observations,
  stable identity, events, world-scoped explicit landmarks, persistence reload, real TaskGraph
  gateway calls, Brain summary bounds and affected existing session/memory/query/navigation tests.
- REAL_MINECRAFT_GAMETEST + RUNTIME / BRIDGE INTEGRATION:
  `python tools/world-model-e2e.py --loader fabric|forge --gradle <cached-gradle>` after building
  `:runtime:runtime-app:installDist`. A deterministic external MCP client reads real players,
  entities, threats, drops and containers; inspects a live chest; executes two real navigation
  graphs; and verifies that fixture-driven cow death/container replacement invalidate the model.
  The GameTest verifies physical arrival, actual route steps and published behavior completion.
  Arenas, entity spawn/no-AI settings, difficulty and environmental changes are fixtures;
  player navigation is executed normally. No teleport or inventory/world edits fake movement.
  Default standalone GameTest entry also checks real observation and dead-entity exclusion.
- Development failures were diagnosed as an incomplete truncation marker (PRODUCT), an entity
  index readiness condition (FIXTURE), mismatched fractional versus submitted integer destination
  (TEST_ASSERTION), missing Gson-facade methods (PRODUCT/COMPATIBILITY), and peaceful difficulty
  removing the hostile fixture (FIXTURE). Final regression also corrected the old automatic
  container-landmark assertion (TEST_ASSERTION) and required all relevant entity identities in
  the fixture/bridge readiness predicate (FIXTURE/HARNESS), retaining the original coverage.
  Each was corrected before rerunning its affected check.

Final regression results on 2026-09-02:

| Verification | Result |
| --- | --- |
| Focused Runtime unit/integration tests | 39 passed; zero failures, errors or skipped tests. |
| Shared navigation controller tests | 7 passed; zero failures, errors or skipped tests. |
| Runtime installDist | Passed. |
| Fabric 1.21.1 real Runtime/Bridge/GameTest chain | Passed; 1/1 GameTest, both navigation graphs completed and actual observation/invalidation assertions passed. |
| Forge 1.20.1 real Runtime/Bridge/GameTest chain | Passed; 2/2 GameTests (including required unknown-Mod fixture), both navigation graphs completed and actual observation/invalidation assertions passed. |
| Forge production bridge artifact check | Passed; production artifact retains the Gson-only bridge boundary. |

Generated evidence lives under
`artifacts/codex-verification/feature7-*` and `world-model-{fabric,forge}-*` (not committed).
The canonical overall completion matrix remains `RC_COMPLETION_MATRIX.md`.

LIVE_PROVIDER: NOT_RUN. HUMAN_PLAYTEST: NOT_RUN. No later execution file is included.
