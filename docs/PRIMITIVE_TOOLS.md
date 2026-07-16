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

Still required for RC:

- dynamic `registry.search/describe` and `recipe.query`;
- movement look/stop/step;
- block inspect/interact/break/place;
- inventory transfer/drop;
- item inspect/use;
- entity inspect/interact/collect/attack;
- menu session inspect/click/quick-move/close;
- explicit safety retreat Tool and remaining task wait/checkpoint controls;
- real Fabric tests for each mutating primitive, unknown namespace coverage, cancellation, budgets,
  world/inventory deltas, and composite-to-primitive equivalence.
