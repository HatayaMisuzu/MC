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

External Brain adapter capabilities are not identical. Hermes `mcac-brain/1` has structured
semantic state and completion-claim fields. The OpenAI-compatible adapter supports bounded tool
calling and `ASK_USER`, but its final response is currently natural-language content without those
Hermes-specific structured fields. Replay is deterministic test evidence, never Live-provider
evidence.
