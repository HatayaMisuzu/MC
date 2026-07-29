# MCAC 0.3.1 compatibility

The machine-readable support source is
[`product/PRODUCT_TRUTH.json`](product/PRODUCT_TRUTH.json). “Full Bridge” means the authenticated
Runtime/body/Tool path is an automated release gate; it does not mean Live-provider or human-play
verification has occurred.

| Minecraft | Loader | Java | Product mode | Current automated boundary |
|---|---|---:|---|---|
| 1.21.1 | Fabric | 21 | `FULL_RUNTIME_BRIDGE` | Loader lifecycle, GameTest, Runtime E2E, persistence recovery, Registry/Observation and bounded primitive Tools |
| 1.20.1 | Forge | 17 | `FULL_RUNTIME_BRIDGE` | Loader lifecycle, GameTest, Runtime E2E, persistence recovery, Registry/Observation and bounded primitive Tools |
| 1.21.1 | NeoForge | 21 | `LOCAL_ONLY` | Local body/commands, packaging and diagnosis; no Full Runtime Bridge claim |

Other versions, Loaders and modpacks may be detected and diagnosed, but are not claimed as Full
Bridge targets. Unknown Mod content is handled as connected Registry/recipe/Observation data and
generic interaction; MCAC does not promise support for every third-party screen or mechanic and
does not create one Java Handler per Mod.

Compatibility-pack fixtures prove the declaration-only Compatibility Host lifecycle, not arbitrary
Create, AE2, Mekanism or modpack compatibility. Exact row-level evidence and remaining gaps are in
[`RC_COMPLETION_MATRIX.md`](RC_COMPLETION_MATRIX.md).

Pack loading is no-extraction and fail-closed: an archive is limited to 8 MiB compressed, 256 files,
1 MiB per file and 16 MiB of bytes actually inflated across the whole archive. Exactly one of
`manifest.yaml` and `manifest.yml` is required. JSON/YAML input rejects duplicate keys, trailing or
multiple documents, excessive depth/name/number/string/token sizes, and YAML anchors/aliases.
Declared ZIP sizes are not trusted for the byte budget.

External Brain adapter capabilities are not identical. Hermes `mcac-brain/1` has structured
semantic state and completion-claim fields. The OpenAI-compatible adapter supports bounded tool
calling and `ASK_USER`, but its final response is currently natural-language content without those
Hermes-specific structured fields. Replay is deterministic test evidence, never Live-provider
evidence.

The exact machine-readable comparison is
[`product/BRAIN_ADAPTER_CAPABILITIES.json`](product/BRAIN_ADAPTER_CAPABILITIES.json). The first
real third-party pack remains a future acceptance item; its strict manifest starting point,
bounded test path and evidence labels are documented in
[`compatibility/FIRST_REAL_PACK_ACCEPTANCE.md`](compatibility/FIRST_REAL_PACK_ACCEPTANCE.md).
