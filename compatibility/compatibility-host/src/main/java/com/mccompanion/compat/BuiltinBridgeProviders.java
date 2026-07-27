package com.mccompanion.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Trusted descriptors for existing Loader modules; this registry does not reimplement their bodies. */
public final class BuiltinBridgeProviders {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BuiltinBridgeProviders() {}

    public static List<CompatibilityPack> all() {
        return List.of(
                descriptor("builtin.minecraft.1.21.1", "1.21.1",
                        CompatibilityPack.PackType.MINECRAFT_BASE, "", "", "LOCAL_PRIMITIVES",
                        List.of("world.observe", "registry.read", "inventory.inspect")),
                descriptor("builtin.fabric.1.21.1", "1.21.1",
                        CompatibilityPack.PackType.LOADER, "fabric", "0.19.3", "FULL_RUNTIME_BRIDGE",
                        List.of("runtime.bridge", "task.execute", "menu.interact")),
                descriptor("builtin.minecraft.1.20.1", "1.20.1",
                        CompatibilityPack.PackType.MINECRAFT_BASE, "", "", "LOCAL_PRIMITIVES",
                        List.of("world.observe", "registry.read", "inventory.inspect")),
                descriptor("builtin.forge.1.20.1", "1.20.1",
                        CompatibilityPack.PackType.LOADER, "forge", "47.4.10", "FULL_RUNTIME_BRIDGE",
                        List.of("runtime.bridge", "task.execute", "menu.interact")),
                descriptor("builtin.neoforge.1.21.1", "1.21.1",
                        CompatibilityPack.PackType.LOADER, "neoforge", "21.1.235", "LOCAL_ONLY",
                        List.of("world.observe", "inventory.inspect")));
    }

    private static CompatibilityPack descriptor(
            String id, String minecraft, CompatibilityPack.PackType type,
            String loader, String loaderVersion, String mode, List<String> capabilities) {
        ObjectNode document = JSON.createObjectNode().put("schemaVersion", "mcac-capabilities/1");
        ArrayNode values = document.putArray("capabilities");
        capabilities.forEach(capability -> values.add(JSON.createObjectNode()
                .put("id", capability).put("kind", "builtin-provider")
                .put("risk", "LOW").put("enabled", true)
                .set("contract", JSON.createObjectNode().put("mode", mode))));
        CompatibilityPack.Manifest manifest = new CompatibilityPack.Manifest(
                "mcac-compat/1", id, "1.0.0", type, id, "mcac-builtin",
                Instant.parse("2026-07-27T00:00:00Z"),
                new CompatibilityPack.Target(minecraft, "", loader, loaderVersion,
                        null, null, "", Map.of(), "", "", "", ""),
                new CompatibilityPack.RuntimeDeclaration(1, false, true, false, "", List.of()),
                new CompatibilityPack.Permissions(
                        Set.of("REGISTRY_READ", "WORLD_OBSERVE"),
                        Set.of("SHELL", "ARBITRARY_FILE_ACCESS", "DIRECT_WORLD_EDIT",
                                "CREDENTIAL_READ", "PROCESS_EXEC", "NETWORK_UNBOUNDED")),
                List.of(), List.of(), List.of(), List.of(), List.of(), 0,
                mode.equals("LOCAL_ONLY")
                        ? List.of("Runtime control lease is not available for this Loader descriptor.")
                        : List.of());
        String hash = CompatibilityPackLoader.sha256((id + ':' + mode).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new CompatibilityPack(manifest, hash,
                Map.of("capabilities/tools.json", document), Map.of());
    }
}
