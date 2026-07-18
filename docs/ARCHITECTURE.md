# MCAC architecture

Updated: 2026-07-16

## Authority boundary

```text
Player / game chat / HTML control terminal
                    │
                    ▼
MCAC Runtime
├─ bounded context and conversation
├─ typed memory and Search Gateway
├─ primitive Tool Gateway
├─ deterministic Task Graph Runtime
├─ isolated Skill Workspace
├─ safety, persistence, audit and verification
└─ product/diagnostic APIs
                    │
                    ▼
Hermes / DeepSeek / another external LLM or Agent
```

The external Brain is the only high-level decision-maker.

```text
Task Graph Runtime = deterministic orchestration
External Brain = reasoning and planning
```

MCAC can validate and execute a Brain-authored graph, enforce limits and permissions, dispatch
primitive Minecraft actions, pause/cancel/retry/checkpoint, reconcile after restart, and return
verified observations. It cannot invent an open-ended goal, choose a high-level strategy, maintain
a competing personality/plan, or modify production source.

## Runtime and body layers

```text
External Brain adapters
  Hermes mcac-brain/1 · OpenAI-compatible · explicit non-Live Replay
                              │
Runtime Tool Gateway / MCP / authenticated Brain ingress
                              │
Task Graph Runtime · Skill Workspace · Memory · Search · Audit
                              │ authenticated WebSocket
Minecraft loader bridge (Fabric full bridge; Forge/NeoForge LOCAL_ONLY)
                              │
Version body / CompanionPlayer / behavior lifecycle
                              │
Primitive executors + SafetyExecutor + ObservationVerifier
                              │
PlayerActionGateway + ActionEvidence
                              │
Vanilla/Mod player interaction paths
```

Current code still contains composite capabilities such as mining, smelting, storage, crafting, and
defense. They are compatibility conveniences with real vanilla behavior evidence. They must migrate
to shared primitive executors or declarative built-in Skills and must not remain the only path.

## Tool, Task Graph, and Skill relationship

```text
Primitive Tool
  one bounded observable action
        ▲
        │ composed by
Declarative Skill
  reusable Task Graph + permissions + provenance + trust state
        ▲
        │ selected/authored/revised by
External Brain Task Graph
  goal-specific deterministic orchestration
```

Wood, diamonds, shelter construction, chest organization, smelting, defense, and unknown Mod
resources are acceptance scenarios. They do not define production Handler classes.

## Runtime permission boundary

The external Brain receives logical Workspace resources and bounded MCAC Tools, never host authority.
Generated Skill drafts are addressed by logical Skill ID and stored in profile/companion-isolated
Runtime scopes. Drafts remain quarantined until the real Task Graph validator accepts their
document; validation alone does not promote, execute, or expand permissions. See
`docs/AGENT_WORKSPACE.md`. The Brain may request promotion but cannot approve it; approval and
rejection belong to the authenticated local management boundary, and rollback is limited to a
previously approved version.
No runtime Tool exposes arbitrary Shell, Git, Gradle, compiler/process execution, arbitrary files,
production source, browser cookies, launcher credentials, Minecraft save mutation, direct inventory
editing, or direct world editing. Search is a separate authorized gateway, not arbitrary networking.
Task Graph permissions must be known MCAC permissions and must include each current
`ToolDefinition.permission`. Literal arguments are checked against the current Tool schema during
validation, and resolved arguments are checked again immediately before dispatch.

## Registry and Mod compatibility

Generic Mod support comes from Registry IDs and schemas reported by the connected body: blocks,
items, entity types, recipes, menu types, tags, dimensions, tool requirements, and observable
components. MCAC may promise observation and generic interaction only; unknown behavior must return
an honest failure/observation. Ordinary Registry content must not require one Java adapter per Mod.

The current Fabric bridge advertises live `registry_query`, `recipe_query`, and
`primitive_observation_query` support. A
capability-gated Runtime gateway dispatches bounded read-only query commands to that authenticated
session and accepts results only when query ID, Runtime session, companion, Brain call, timeout, and
result-size boundaries still match. `registry.search`, `registry.describe`, and `recipe.query`
therefore observe unknown namespaces from the live server Registry/recipe manager; they do not
consult a static compatibility list or grant the external Brain direct loader access. Other loaders
must remain honest by omitting those capabilities until their bridge supplies the same contract.
The same authenticated route supplies `block.inspect`, `item.inspect`, and `entity.inspect` from the
actual spawned body. Fabric enforces loaded-world, distance, visibility, type, UUID, and result-count
bounds again on the server thread, so Runtime validation is not the only security boundary.

## Safety

Emergency reflexes for immediate hazards may preempt normal graph execution:

```text
Emergency Reflex > User Cancel > Explicit Safety Tool > Normal Task Graph
```

Reflexes and explicit `safety.*` Tools share executors and observation formats. A reflex may pause
dangerous work but cannot silently replace the long-term goal; the external Brain decides recovery.

## Product reuse

The repository already contains an HTML control terminal, installer, updater, rollback, uninstaller,
Doctor, support-bundle, multi-loader build, and release packaging. New Task Graph/Skill/permission
features extend these surfaces rather than creating parallel products.

## Layer invariants

- `pure-core` does not reference Minecraft or Loader APIs.
- Runtime does not reference Minecraft classes.
- Normal world-changing actions pass through `PlayerActionGateway` and real player interaction.
- Body-control primitives such as `movement.look` also use the durable lease/task path and
  `PlayerActionGateway`; a returned success includes a verified post-action body observation.
- Generic block/entity interaction is capability-gated per loader, bound to the durable lease/task,
  and rechecks dimension, loaded state, range, visibility, UUID, hand, and face immediately before
  invoking vanilla `ServerPlayer` interaction. It does not contain block- or Mod-specific handlers.
- Asynchronous results are revalidated against world, dimension, lease, behavior revision,
  companion, and owner.
- Runtime failure degrades the body to `LOCAL_ONLY`/`SAFE_IDLE`; it must not prevent Mod loading.
- Replay is automation evidence, not Live-provider or human-play evidence.
