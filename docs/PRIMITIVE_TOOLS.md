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
| `registry.search` | Bounded namespace-aware ITEM/BLOCK/ENTITY/DIMENSION/MENU search over the authenticated connected server |
| `registry.describe` | Exact namespaced identifier lookup with bounded type-specific details from the connected server |
| `recipe.query` | Bounded crafting/smelting recipe query over the connected server recipe manager |
| `block.inspect` | Visible loaded block within 16 blocks, including live Registry ID, state properties, fluid, collision, replaceability, and destroy speed |
| `item.inspect` | Exact live Registry item definition plus matching connected-body inventory slots, counts, selection, and durability |
| `entity.inspect` | Visible live entities within a declared 1..16-block radius, with bounded type/UUID filtering, distance, position, health, or dropped-item data |

Every body-derived result identifies `CONNECTED_BODY_OBSERVATION`, retains the observation time and
dimension when present, and returns `OBSERVATION_UNAVAILABLE` instead of inventing missing data.
Capability state is not inferred from a Tool name alone: it is computed from formal implementation,
the authenticated Mod handshake, Minecraft/loader support, body declaration, Runtime connectivity,
and the current spawned-body status.

The Registry and spatial observation Tools are exposed only when the authenticated Mod handshake
advertises `registry_query`/`recipe_query`/`primitive_observation_query`. Runtime binds each result
to the Brain session, companion, Runtime
session, query ID, size limit, and timeout. Fabric 1.21.1 currently supplies live Registry and recipe
observations plus block/item/entity inspection; loaders without that implementation do not advertise
the Tools. Unknown namespaces are discovered through the live Registry and actual connected body
rather than a per-Mod adapter or fabricated observation.

Existing action Tools still include movement, bounded scan, collect, vein mining, smelting,
inventory withdraw/deposit/deliver, crafting, eating, owner defense, and task controls. Several are
currently composite compatibility entries and are not evidence that the block/entity/menu/registry
primitive families are complete.

The first bounded mutation entry points now reuse those same connected-body executors:

| Tool | Shared executor and boundary |
|---|---|
| `movement.step` | Resolves a relative `dx/dy/dz` in `-8..8` from the latest connected-body position, then dispatches an ordinary survival `TRAVEL`; it never teleports |
| `movement.look` | Runs through the durable lease/task path, turns the body toward a bounded same-dimension position with vanilla entity rotation, and verifies the resulting view vector |
| `movement.stop` | Is exposed only when durable task state is attached and cancels only an active `TRAVEL`, `FOLLOW`, or `RETURN` task |
| `block.break` | Converts one observed namespaced block position into `MineResourceVein` with `quantity=1`, preserving vanilla hardness, tool, drop, pickup, and evidence behavior |
| `block.interact` | Performs one same-dimension, loaded, visible interaction within five blocks through `ServerPlayerGameMode.useItemOn`; face and hand are explicit and bounded |
| `entity.collect` | Uses the existing bounded `CollectResource` movement and vanilla `ItemEntity` pickup executor |
| `entity.interact` | Performs one UUID-bound, alive, visible entity interaction within five blocks through `ServerPlayer.interactOn`; the target is revalidated immediately before use |
| `inventory.transfer` | Selects the existing verified-container withdraw or deposit executor from the declared direction; arbitrary container or filesystem access is impossible |

These aliases are convenience primitive entry points, not new scenario Handlers. Runtime tests
capture their actual Mod protocol payloads, including unknown namespaced IDs. Existing real Fabric
GameTests verify the shared movement, mining, pickup, and container executors; direct per-alias
Runtime/Fabric E2E remains part of the RC gap.

Still required for RC:

- block place;
- inventory drop;
- item use;
- entity attack;
- menu session inspect/click/quick-move/close;
- explicit safety retreat Tool and remaining task wait/checkpoint controls;
- cross-loader Registry query support plus tags/tool-requirement/component breadth;
- real Fabric tests for each mutating primitive, cancellation, budgets, world/inventory deltas, and
  composite-to-primitive equivalence.
