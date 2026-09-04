# Feature 6 combat closeout

Scope: execution file 08, continuing checkpoint `fd52d36ca43d5d4d9d1dbfd579eb49294b3de139`.
This is the single Feature 6 system audit; no subsequent feature is included.

## Audit

| Requirement | Result and correction |
| --- | --- |
| Melee, shield, bow | Retained continuous shared executor, stable explicit UUID, vanilla reach/cooldown, menu equipment, shield cycling, projectile creation and observed damage/death. Strengthened shield GameTest with actual expired spawn protection and an unshielded damage control. |
| Creeper, Skeleton, melee, multiple enemies | Finite shared priorities; bounded live observation; 8/16/7-block clearance; escape considers all currently unsafe identities. No per-scenario Handler or internal planner. |
| Low health, eating, recovery | 30% entry / 60% exit; real navigation, menu food selection, vanilla consumption and health regeneration; 1200 active ticks maximum; missing food and inaccessible escape fail explicitly. |
| Previous durable task | Local interruption retains original behavior ID, route, combat session and blueprint completion set. Recovery restores the original control identity and resumes existing executors. NBT round-trip and actual completed world blocks verify retained steps. |
| Owner attacked | Recent vanilla owner damage triggers bounded shared combat; explicit `DefendOwner` now uses the same executor, avoiding interference from generic proximity reflexes. |
| Events and Brain boundary | Feature 3 event admission, priority, dedupe and cooldown remain. Authenticated local-handling metadata suppresses redundant Brain escalation only for the listed handled conditions. Other hazards and actual failed/blocked task lifecycle remain visible. |
| Failure, pause, cancel, disconnect, death | Held movement/item input is cancelled; explicit lifecycle controls abort automatic recovery. Navigation failures preserve resumable work and report failure. Existing restart quarantine remains; transient combat is not claimed restart-resumable. |
| Runtime → Bridge → Body | The real external-client recovery graph exposed the existing `BuildSmallBlueprint` formal registration as DECLARED despite implemented body support. Corrected that single registration and added connected-capability verification so the durable recovery task is callable. |
| Dual version | Shared finite policy; separate Minecraft bindings for item food components (1.21.1) and edible-item properties (1.20.1). Loader compilation and test execution remain serial. |

## Evidence

The single final feature regression passed on 2026-09-02. The durable source of overall product
status remains `RC_COMPLETION_MATRIX.md`. Commands ran serially with the existing trusted Gradle
9.5.1 cache, Java 21 and `--offline --console=plain --no-parallel`; the default wrapper's offline
plugin resolution was unavailable in the restricted environment.

- UNIT / INTEGRATION: shared combat and threat policies, Runtime tool dispatch, connected capability
  visibility, durable survival/player-entity events and Brain-dispatch eligibility.
- REAL_MINECRAFT_GAMETEST: moving-target melee/bow, shield damage control, honest equipment/identity/
  path failures, simultaneous fixed-identity hostiles, ranged clearance, low-health recovery and
  retained blueprint steps, bounded owner defense, pause/cancel and missing-food failures.
- RUNTIME / BRIDGE INTEGRATION: `tools/combat-threat-e2e.py --loader fabric|forge --gradle <executable>`
  uses current `:runtime:runtime-app:installDist`, `JAVA_HOME`, isolated temporary Runtime data and
  generated test worlds. A deterministic MCP client submits an inventory-observation/build/return
  graph; real Minecraft interrupts partial construction, retreats, consumes food, heals and resumes.
  The harness checks graph success, completed durable tasks and exactly one admitted low-health edge.
  External registration/completion waits use wall-clock deadlines because GameTest ticks accelerate.

| Verification | Command / result |
| --- | --- |
| Final UNIT / INTEGRATION | `:core:pure-core:test` filtered to `CombatControllerTest`, `ThreatPolicyTest`; `:runtime:runtime-app:test` filtered to `RuntimeToolGatewayTest`, `CapabilityVisibilityTest`, `SurvivalEventNormalizerTest`, `PlayerEntityEventNormalizerTest`, `RuntimeEventBrainDispatcherPolicyTest`, `RuntimeEventRepositoryTest`. 49 tests passed (10 core, 39 Runtime), zero failures/errors/skips. |
| Final Fabric 1.21.1 | `-p minecraft/fabric-1.21.1 -I tools/combat-tests.init.gradle runGameTest`: 11/11 required registrations passed; 10 substantive Feature 6 cases plus the optional external-harness entry, inactive in this run. |
| Final Forge 1.20.1 | `-p minecraft/forge-1.20.1 -I tools/combat-tests.init.gradle runGameTestServer`: 12/12 required registrations passed; 10 substantive Feature 6 cases, the inactive optional harness entry and the existing unknown-content fixture test. |
| Real Runtime / Bridge recovery | Both `--loader fabric` and `--loader forge` harness runs passed: graph `SUCCEEDED`, durable behavior `COMPLETED`, exactly one admitted locally handled low-health event. Minecraft assertions verified retreat, consumption, healing and completion of the same partially built blueprint. |
| Existing safety regression | A subsequent source check found the old Fabric safety test still required permanent pause after automatic retreat. Updated it to require the same running behavior and recovered control, then passed that one case (1/1) through a temporary test entry, removed afterward. No second system audit or full regression. |

Local logs: `artifacts/codex-verification/feature6-final-unit-integration.log`,
`feature6-final-fabric.log`, `feature6-final-forge.log`, `feature6-legacy-safety-focused.log`.
Real-chain evidence: `artifacts/codex-verification/threat-fabric-bridge-evidence.json` and
`threat-forge-bridge-evidence.json`. These generated artifacts are not committed; the harness and
focused regression filter are committed for reproduction.

LIVE_PROVIDER: NOT_RUN. HUMAN_PLAYTEST: NOT_RUN. Process-restart combat continuation: NOT_RUN;
existing explicit recovery remains required. No attributes are boosted and no threat is deleted to
make a test pass; arena/equipment/hunger, hostile AI isolation and scripted hostile motion are labeled
fixtures. Fixture entities are removed only after assertions for cleanup.
