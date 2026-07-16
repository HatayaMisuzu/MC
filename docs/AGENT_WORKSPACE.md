# Agent Workspace and generated Skill drafts

The Runtime external Brain does not receive a host filesystem path. `AgentWorkspace` maps logical
resources into a Runtime-owned root derived from the configured database directory and isolates
them by hashed profile and companion scopes.

Current Tool surface:

```text
skill.list
skill.read
skill.save_draft
skill.validate
skill.request_promotion
skill.disable
skill.rollback
skill.execute
```

`skill.save_draft` accepts only a bounded declarative JSON/YAML Task Graph document and stores it
as `QUARANTINED`. `skill.validate` parses the stored document with the real Task Graph codec and
validates it against the current Runtime node set, Tool definitions, permissions, and input
schemas. A successful validation reports `GENERATED_VALIDATED`; it does not promote or execute the
draft and does not grant additional permissions.

`skill.request_promotion` persists a numbered `PENDING_REVIEW` version with the controller and
Brain session, graph provenance, declared permissions, complete validation result, and document
SHA-256. There is deliberately no external-Brain approval Tool. The authenticated loopback
`/skills` management endpoint is the local-user boundary for listing review records, approving or
rejecting a request, disabling an active version, and rolling back to a previously approved
version. Approval records `LOCAL_MANAGEMENT_USER`; rollback cannot activate a draft that was never
approved.

`skill.execute` loads only the current ACTIVE generated version or a read-only built-in resource,
rechecks the document SHA-256, and starts it through the same persistent asynchronous Task Graph
Runtime. It therefore inherits current Tool availability, permission/schema validation,
checkpoints, pause/cancel, recovery, Evidence budgets, and result reconciliation. Generated Skills
cannot call any `skill.*` Tool, preventing recursive execution and self-modifying lifecycle actions.

The initial built-in catalog packages six existing composite capabilities as declarative,
read-only compatibility Skills: `collect_resource`, `mine_vein`, `smelt_item`,
`withdraw_storage`, `craft_item`, and `defend_owner`. They are not new scenario Handlers and do not
replace the Primitive Tool work; each is a Task Graph wrapper over the existing bounded Tool.

Workspace invariants:

- physical roots are selected by Runtime configuration and are never returned by a Tool;
- profile and companion scopes are separate and use derived physical names;
- logical paths use a small ASCII segment grammar and only `.yaml`, `.yml`, `.json`, and `.md`;
- absolute paths, drive paths, backslashes, `..`, Windows ADS, reserved device names, control/format
  characters, non-NFC names, links, reparse points, and scope escapes are rejected;
- default quotas are 128 resources, 2 MiB total, and 64 KiB per resource;
- content writes and the logical index use same-directory atomic replacement where supported;
- unchanged content keeps its version; changed content increments the version, records SHA-256,
  and keeps the previous content in a Runtime-only backup area;
- reads verify the persisted SHA-256 before returning content.

This slice does not yet implement the terminal review UI, signed/administrator promotion policies,
temporary execution policy, workspace migration tooling, or backup retention controls. Those
remain visible in `docs/RC_COMPLETION_MATRIX.md`.
