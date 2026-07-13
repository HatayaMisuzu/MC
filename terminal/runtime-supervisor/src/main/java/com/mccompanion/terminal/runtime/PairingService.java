package com.mccompanion.terminal.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

public final class PairingService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    public void configure(MinecraftInstance instance, RuntimeProfile profile) throws IOException {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Path instanceState = instance.gameDirectory().resolve(".mccompanion");
        Path tokenFile = instanceState.resolve("runtime.token");
        Files.createDirectories(instanceState);
        Files.createDirectories(instance.configDirectory().resolve("minecraft-ai-companion"));
        Files.createDirectories(profile.profileDirectory());
        writePrivate(tokenFile, token + System.lineSeparator());
        writePrivate(profile.profileDirectory().resolve("pairing.token"), token + System.lineSeparator());
        ObjectNode config = JSON.createObjectNode().put("schemaVersion", 1).put("enabled", true)
                .put("runtimeUrl", "ws://127.0.0.1:" + profile.port()).put("installationId", instance.instanceId())
                .put("instanceId", instance.instanceId()).put("tokenFile", "../../.mccompanion/runtime.token")
                .put("safeIdleOnDisconnect", true).put("connectTimeoutSeconds", 15);
        JSON.writerWithDefaultPrettyPrinter().writeValue(instance.configDirectory().resolve("minecraft-ai-companion/runtime.json").toFile(), config);
        String yaml = "server:\n  bind: 127.0.0.1\n  port: " + profile.port() + "\n  token_file: ./pairing.token\n  heartbeat_seconds: 15\n  allow_remote: false\n"
                + "database:\n  path: ./companion.db\nprovider:\n  mode: rules\n  base_url: https://api.openai.com\n  api_key_env: MC_COMPANION_API_KEY\n  model: disabled\n  timeout_seconds: 60\n"
                + "logging:\n  file: ./runtime.log\n  console: false\n";
        Files.writeString(profile.configFile(), yaml, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    private static void writePrivate(Path file, String value) throws IOException {
        Files.writeString(file, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
            if (acl != null) {
                var owner = Files.getOwner(file);
                var entry = java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW)
                        .setPrincipal(owner).setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build();
                acl.setAcl(java.util.List.of(entry));
            }
        } catch (UnsupportedOperationException ignored) { }
    }
}
