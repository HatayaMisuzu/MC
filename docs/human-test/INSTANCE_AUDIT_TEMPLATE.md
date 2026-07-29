# Human-test instance audit template

Status: Template only. Store completed audits under the ignored local validation directory; never
commit real launcher, account, host, instance, path, UUID, or file-hash evidence.

Audit time: `<TIMESTAMP>`
Authorized test root: `<TEST_ROOT>`
Game directory: `<GAME_DIR>`
Instance identifier: `<INSTANCE_ID>`
Method: read-only preflight; do not read account credentials.

## Detected environment

- Launcher: `<LAUNCHER>`
- Minecraft / Loader / Java: `<VERSIONS>`
- MCAC installation state: `<STATE>`
- Running-process state: `<STATE>`
- World and Mod scope: `<SUMMARY_WITHOUT_PERSONAL_PATHS>`

## Compatibility decision

Record whether the instance exactly matches a supported target. Do not modify an unsupported or
personal instance in place. For a human test, create an isolated game directory below
`<TEST_ROOT>` and keep launcher accounts and personal worlds out of scope.

## Privacy and rollback

- Do not record file hashes from a personal installation.
- Do not copy account databases, tokens, cookies, launcher profiles, email addresses, host names,
  network addresses, or support bundles into this template.
- Record only the planned MCAC-created files and the reversible uninstall/cleanup procedure.
