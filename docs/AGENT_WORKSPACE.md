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
```

`skill.save_draft` accepts only a bounded declarative JSON/YAML Task Graph document and stores it
as `QUARANTINED`. `skill.validate` parses the stored document with the real Task Graph codec and
validates it against the current Runtime node set, Tool definitions, permissions, and input
schemas. A successful validation reports `GENERATED_VALIDATED`; it does not promote or execute the
draft and does not grant additional permissions.

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

This slice does not yet implement promotion approval, immutable promoted versions, revocation,
rollback, temporary execution policy, workspace migration tooling, or the terminal review UI.
Those remain visible in `docs/RC_COMPLETION_MATRIX.md`.
