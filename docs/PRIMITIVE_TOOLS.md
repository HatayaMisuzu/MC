# Primitive Tool coverage

Primitive Tools expose bounded observations or game actions. They are not goals, plans, scenario
Handlers, or fabricated state changes. Composite capabilities may remain as compatibility Tools or
built-in Skills while their underlying primitive families are completed.

Current read-only observation primitives:

| Tool | Source and boundary |
|---|---|
| `world.observe` | Complete latest connected-body status |
| `world.query` | One bounded section: position, vitals, inventory, observed containers, behavior, or all |
| `inventory.inspect` | Connected-body inventory counts and free-slot observation |
| `safety.inspect` | Connected-body vitals plus deterministic fire/lava/low-air/low-health flags; it explicitly does not claim a threat scan |
| `task.inspect` | Current durable Runtime task and at most 16 recent events, or `IDLE` |
| `capability.list` | Formal Runtime capability registry intersected with the connected loader/body/current status |
| `capability.describe` | One formal definition plus its current lifecycle state |

Every body-derived result identifies `CONNECTED_BODY_OBSERVATION`, retains the observation time and
dimension when present, and returns `OBSERVATION_UNAVAILABLE` instead of inventing missing data.
Capability state is not inferred from a Tool name alone: it is computed from formal implementation,
the authenticated Mod handshake, Minecraft/loader support, body declaration, Runtime connectivity,
and the current spawned-body status.

Existing action Tools still include movement, bounded scan, collect, vein mining, smelting,
inventory withdraw/deposit/deliver, crafting, eating, owner defense, and task controls. Several are
currently composite compatibility entries and are not evidence that the block/entity/menu/registry
primitive families are complete.

The first bounded mutation entry points now reuse those same connected-body executors:

| Tool | Shared executor and boundary |
|---|---|
| `movement.step` | Resolves a relative `dx/dy/dz` in `-8..8` from the latest connected-body position, then dispatches an ordinary survival `TRAVEL`; it never teleports |
| `movement.stop` | Is exposed only when durable task state is attached and cancels only an active `TRAVEL`, `FOLLOW`, or `RETURN` task |
| `block.break` | Converts one observed namespaced block position into `MineResourceVein` with `quantity=1`, preserving vanilla hardness, tool, drop, pickup, and evidence behavior |
| `entity.collect` | Uses the existing bounded `CollectResource` movement and vanilla `ItemEntity` pickup executor |
| `inventory.transfer` | Selects the existing verified-container withdraw or deposit executor from the declared direction; arbitrary container or filesystem access is impossible |

These aliases are convenience primitive entry points, not new scenario Handlers. Runtime tests
capture their actual Mod protocol payloads, including unknown namespaced IDs. Existing real Fabric
GameTests verify the shared movement, mining, pickup, and container executors; direct per-alias
Runtime/Fabric E2E remains part of the RC gap.

Still required for RC:

- dynamic `registry.search/describe` and `recipe.query`;
- movement look;
- block inspect/interact/place;
- inventory drop;
- item inspect/use;
- entity inspect/interact/attack;
- menu session inspect/click/quick-move/close;
- explicit safety retreat Tool and remaining task wait/checkpoint controls;
- real Fabric tests for each mutating primitive, unknown namespace coverage, cancellation, budgets,
  world/inventory deltas, and composite-to-primitive equivalence.
