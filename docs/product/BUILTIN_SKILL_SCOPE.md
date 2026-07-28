# Built-in Skill scope and future content acceptance

The six bundled Skills are declarative compatibility wrappers over existing bounded Tools:
`collect_resource`, `craft_item`, `defend_owner`, `mine_vein`, `smelt_item`, and
`withdraw_storage`. They are not a complete survival guide, do not choose a player's goal or
strategy, and do not grant authority beyond each graph's declared permission.

`BuiltinSkillCatalogTest` parses every bundled YAML document, checks the exact current Tool alias
and permission, validates it as an executable `mcac-task-graph/1` graph, prevents overwrite, and
executes a built-in through the same persistent Task Graph Runtime.

Future basic-survival content is accepted only as small external-Brain-selectable Skills with:

- explicit inputs, permission and bounded resource/time/state budgets;
- composition from generic primitive Tools and existing Skill reuse, never scenario Java Handlers;
- real Loader or Runtime integration evidence for every mutation;
- cancellation, insufficient-resource, restart/reconciliation and final-observation cases;
- honest scope text that does not imply autonomous strategy or universal Mod support.

Candidate chains include observation-led shelter preparation, food/fuel preparation and
storage-to-crafting composition. They remain future content until those tests exist.
