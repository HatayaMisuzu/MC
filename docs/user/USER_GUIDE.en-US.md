# MCAC user guide (English)

Applies to: 0.3.1 (automated baseline frozen and published; one live-Brain vertical slice verified,
long-duration human play still pending)

## Get started

1. Extract the complete `mcac-release.zip`; do not copy only `mcac.exe`.
2. Start it either way: in PowerShell run `.\mcac.exe web --open-browser` to explicitly allow
   this launch to open the system default browser, or double-click `启动终端.cmd` (equivalent
   to `mcac.exe web --open-browser`).
3. Select the detected PCL2 or HMCL instance under Launchers & instances.
4. Run Diagnostics, then review and confirm the installation plan.
5. Start Runtime and verify that the UI shows a real PID, ports and authenticated health.
6. When connecting the game, follow the preflight and launch plan on Game launch.

Double-clicking or starting `mcac.exe` without arguments starts or reuses the local Terminal and
opens its control page. Explicit `web` mode remains headless unless `--open-browser` is supplied.
`MCAC_NO_BROWSER=true` is an unconditional safety override for development, tests, and unattended
runs (it also applies to
`启动终端.cmd`). The service listens only on `127.0.0.1`. Do not share bootstrap URLs, session
cookies, CSRF tokens, launcher credentials or API keys.

## Supported targets

- Fabric 1.21.1 / Java 21: Full Runtime Bridge.
- Forge 1.20.1 / Java 17: Full Runtime Bridge.
- NeoForge 1.21.1 / Java 21: `LOCAL_ONLY`; it never pretends to be fully connected.

Detection of another version does not imply that it can be installed or controlled. Fixture
evidence on the Compatibility page does not establish support for arbitrary third-party Mods.

## Update, repair and uninstall

Every write displays a plan before confirmation. Updates create rollback points; Verify and repair
touches only MCAC-managed files. The two uninstall choices are intentionally separate:

- Uninstall and keep data removes managed application files and retains the MCAC profile.
- Uninstall and delete MCAC data removes only the current instance's MCAC data.
- Worlds, accounts and unrelated Mods are outside both deletion scopes.

## External models

Hermes, DeepSeek or another external LLM/Agent is the high-level decision-maker. Configure
credentials only through environment variables or Windows Credential Manager. Never place them in
the repository, chat, screenshots or a support bundle. On 2026-08-01, PCL + Forge 1.20.1 + Runtime
+ Hermes + the official DeepSeek API passed a bounded live vertical slice covering handshake,
recovery, world observation, Follow, Navigate position change, safe idle, and reconnect. Fabric,
other providers, broad Tool/terrain coverage, and sustained subjective human play remain outside
that result.

## Troubleshooting

Run Diagnostics first, retain the technical error code, and create a redacted support bundle from
Logs & support. Do not delete instance files manually as a repair method. See
[Troubleshooting](../TROUBLESHOOTING.md) and [Known limitations](../../KNOWN_LIMITATIONS.md).
