# Companion Runtime setup

Runtime is a local Java application that provides the authenticated Tool Gateway, conversation,
memory/search, persistence and deterministic Task Graph execution boundary. It is not an internal
high-level Agent; strategy and planning remain with the configured external Brain.

## Managed profiles and ports

The HTML terminal assigns each managed instance a stable loopback Runtime port in the inclusive
range `8766–8866` and stores it in that instance's Runtime profile. The paired management/health
port is the Runtime port plus `10000`. Therefore `8766` is the first allocatable port, not a fixed
port shared by every instance. Always use the address shown by the selected profile or Doctor.

The standalone example configuration may still listen on `127.0.0.1:8766`. Services bind only to
loopback by default; do not expose them to a LAN or the public Internet.

## Start and pair

After extracting the Runtime distribution:

```powershell
.\bin\runtime-app.bat --config .\config\runtime.yml
```

The first start creates a pairing token under ignored Runtime data. Never copy the token, its path,
or a bearer value into chat, logs, support bundles or the repository. With the Minecraft server
stopped, use the terminal's pairing flow for the selected profile, then start Runtime before the
Minecraft server. `/companion runtime` (Fabric) or `/mcac runtime` (Forge) reports
`runtime=ONLINE` after the authenticated handshake.

Fabric 1.21.1 and Forge 1.20.1 both support the Full Runtime Bridge. NeoForge 1.21.1 remains
`LOCAL_ONLY`.

## External Brain

An OpenAI-compatible configuration stores only public endpoint/model information and the name of
an environment variable:

```yaml
provider:
  mode: openai-compatible
  base_url: https://api.example.invalid
  api_key_env: MC_COMPANION_API_KEY
  model: <MODEL_ID>
  timeout_seconds: 60
```

Set the named variable only in the Runtime process environment or an approved local credential
store. Provider failure returns a bounded failure and preserves recoverable state; Runtime does not
replace it with an internal Planner. The Runtime external Brain has no Shell, Git, Gradle, arbitrary
filesystem, production-source or arbitrary-network authority.

The OpenAI-compatible adapter supports tool calling and `ASK_USER`, but it does not currently emit
the Hermes protocol's structured semantic state and completion claim. See
[`COMPATIBILITY.md`](COMPATIBILITY.md).

## Stop

Use the terminal's stop action or terminate the Runtime normally. It stops accepting work, persists
pending events and closes WebSocket/SQLite resources before exit.
