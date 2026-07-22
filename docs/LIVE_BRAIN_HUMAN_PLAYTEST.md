# Live Brain and human playtest guide

Updated: 2026-07-22

This is the shortest supported Fabric 1.21.1 validation path for a real external Brain and a human
player. Automated Replay, Fake, Fixture, 105-turn, and soak results are not substitutes for this run.
Use a disposable test world and test account; do not point the test at a private world.

## 1. Verify and unpack the RC

Keep the ZIP and its `.sha256` sidecar together. In PowerShell, from their directory:

```powershell
$expected = (Get-Content .\mcac-release.zip.sha256).Split(' ')[0].Trim().ToLowerInvariant()
$actual = (Get-FileHash .\mcac-release.zip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'RC ZIP SHA-256 mismatch' }
Expand-Archive .\mcac-release.zip .\mcac-live-test
Set-Location .\mcac-live-test\mcac-release
```

Do not continue after a hash mismatch. Do not paste the provider key into a command, JSON file,
result form, screenshot, log, or Support Bundle.

## 2. Install only to a supported Fabric instance

Open `mcac.exe` to use the HTML terminal. Select a disposable Fabric 1.21.1 instance, review the
installation plan, and confirm Install. Do not use this guide to expand the test to Forge,
NeoForge, another Minecraft version, or an arbitrary personal world.

Record the instance ID shown by the Terminal. The equivalent CLI checks are:

```powershell
.\mcac.exe doctor <instance-id>
.\mcac.exe runtime start <instance-id>
```

## 3. Configure the real external Brain safely

Choose an environment variable name and set its value in the environment that will launch MCAC.
The example name below is not a credential:

```powershell
$env:MC_COMPANION_BRAIN_TOKEN = '<set the real value locally; never record it>'
.\mcac.exe provider configure <instance-id> --base-url https://provider.example/v1 --model <model-id> --api-key-env MC_COMPANION_BRAIN_TOKEN --timeout-seconds 30
```

Also set bounded validation controls when the defaults are not appropriate:

```powershell
$env:MCAC_LIVE_BRAIN_ENABLED = 'true'
$env:MCAC_LIVE_BRAIN_MAX_REQUESTS = '24'
$env:MCAC_LIVE_BRAIN_MAX_INPUT_TOKENS = '30000'
$env:MCAC_LIVE_BRAIN_MAX_OUTPUT_TOKENS = '8000'
$env:MCAC_LIVE_BRAIN_MAX_WALL_CLOCK_MINUTES = '15'
$env:MCAC_LIVE_BRAIN_MAX_RETRIES = '2'
```

Rerun Doctor. `brain.provider`, `brain.live_credentials`, `brain.validation_budget`, `mcp.protocol`,
`registry.generic_tools`, `memory.episode_capsules`, and `memory.candidate_review` must be reviewed.
An absent credential must remain an honest `BLOCKED_BY_CREDENTIALS`, never a simulated pass.

## 4. Start Minecraft and attach a Companion

Start the selected instance normally, enter the disposable world, create/spawn a Companion if
needed, and confirm `/companion runtime`, `/companion status`, and `/companion capabilities` show
the expected local Runtime and Fabric body. Open the Brain page in the HTML terminal. Confirm it
shows provider health, reconnect/session state, aggregate context budgets, Capsule summaries, and
the local-only candidate review controls without displaying a key or full Prompt.

## 5. Run the three scenarios

Run each scenario in a new bounded episode and keep only sanitized aggregate evidence.

1. Basic open task: ask the Brain to prepare basic supplies for a short underground trip. It must
   observe first, use available Tools, ask when essential information is missing, and give a short
   natural completion/failure summary.
2. Unknown Mod generic task: install/use the approved test Mod content without giving the target
   Registry ID to the Brain. Ask it to discover the namespace/content, inspect Registry/recipe/tag/
   tool/menu metadata, compose generic primitives, perform one small task, and verify the final
   world or inventory state. A special mechanism may honestly return
   `UNSUPPORTED_GENERIC_INTERACTION`; do not add or pretend a Mod-specific Handler.
3. Interruption recovery: while a task includes ASK_USER, interrupt Brain transport and restart the
   Runtime once; also exercise pause or user cancel. Verify SAFE_IDLE, one restored question, no
   duplicate chat, no duplicate confirmed effect, and a continued or explicitly classified result.

Stop immediately on unsafe movement, repeated mutation, wrong Companion/session control, credential
exposure, uncontrolled spending, or unbounded retry.

## 6. Review Capsule and Memory candidate

On the Brain page, inspect only the Capsule safe summary and evidence counts. If the Brain submits an
`EPISODIC`, `WORLD`, or `PREFERENCE` candidate, confirm Capsule source, type, key, value, confidence,
TTL, and conflict warning. Approve or reject it locally. Confirm rejection writes no verified Memory
and approval is the only path to verified Memory. The Brain must have no review Tool.

## 7. Export sanitized evidence

Use the HTML terminal's Support Bundle action. Inspect the generated archive before sharing it. It
must not contain a key, raw chat, full Prompt, account data, world data, local paths, or full Search
content. A successful sanitized three-scenario report may be placed at:

```text
%LOCALAPPDATA%/MinecraftAICompanion/profiles/<instance-id>/validation/live-brain-report.json
```

Doctor accepts this as Live evidence only when `liveModel` is true and exactly three scenario entries
are marked `PASS`. The report must still omit credentials and raw content.

## Human result form

Overall result: `PASS / FAIL / BLOCKED_BY_CREDENTIALS / STOPPED_FOR_SAFETY`

| Field | Scenario 1 | Scenario 2 | Scenario 3 |
|---|---|---|---|
| Task |  |  |  |
| Completed |  |  |  |
| Brain Tools used |  |  |  |
| User interventions |  |  |  |
| ASK_USER reasonable |  |  |  |
| Error/failure category |  |  |  |
| Repeated action observed |  |  |  |
| Dangerous behavior observed |  |  |  |
| Conversation natural |  |  |  |
| Estimated tokens/cost |  |  |  |
| Needed new generic capability |  |  |  |
| Subjective companion rating |  |  |  |

Also record, without personal identifiers:

- RC SHA-256 and source SHA:
- Fabric/Minecraft versions:
- Provider type and model name (never the key):
- Doctor warnings:
- Capsule ID(s):
- Candidate decision(s) and conflict(s):
- Duplicate confirmed side effects count:
- Reconnect count and Runtime restart result:
- Sanitized Support Bundle filename/hash:
- Tester follow-up notes:

Do not mark human play as passed until a person has actually completed and reviewed this form.
