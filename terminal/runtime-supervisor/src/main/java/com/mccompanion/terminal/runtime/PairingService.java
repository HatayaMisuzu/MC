package com.mccompanion.terminal.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.protocol.security.OwnerOnlyFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PairingService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    public void ensureConfigured(MinecraftInstance instance, RuntimeProfile profile) throws IOException {
        Path instanceState = instance.gameDirectory().resolve(".mccompanion");
        Path tokenFile = instanceState.resolve("runtime.token");
        Files.createDirectories(instanceState);
        Files.createDirectories(instance.configDirectory().resolve("minecraft-ai-companion"));
        Files.createDirectories(profile.profileDirectory());
        Path profileToken = profile.profileDirectory().resolve("pairing.token");
        String instanceValue = readToken(tokenFile);
        String profileValue = readToken(profileToken);
        if (instanceValue != null && profileValue != null && !instanceValue.equals(profileValue))
            throw new IOException("Runtime pairing tokens disagree; use rotate-token to repair safely");
        String token = instanceValue != null ? instanceValue : profileValue != null ? profileValue : newToken();
        if (instanceValue == null) writePrivateAtomic(tokenFile, token);
        if (profileValue == null) writePrivateAtomic(profileToken, token);
        ObjectNode config = JSON.createObjectNode().put("schemaVersion", 1).put("enabled", true)
                .put("runtimeUrl", "ws://127.0.0.1:" + profile.port()).put("installationId", instance.instanceId())
                .put("instanceId", instance.instanceId()).put("tokenFile", "../../.mccompanion/runtime.token")
                .put("safeIdleOnDisconnect", true).put("connectTimeoutSeconds", 15);
        JSON.writerWithDefaultPrettyPrinter().writeValue(instance.configDirectory().resolve("minecraft-ai-companion/runtime.json").toFile(), config);
        Path providerFile=profile.profileDirectory().resolve("provider.json");
        var provider=Files.isRegularFile(providerFile)?JSON.readTree(providerFile.toFile()):JSON.createObjectNode().put("mode","rules");
        String mode=safe(provider.path("mode").asText("rules")),base=safe(provider.path("baseUrl").asText("https://api.openai.com")),env=safe(provider.path("apiKeyEnv").asText("MC_COMPANION_API_KEY")),model=safe(provider.path("model").asText("disabled"));
        int providerTimeout = Math.max(1, Math.min(300, provider.path("timeoutSeconds").asInt(15)));
        Path brainFile = profile.profileDirectory().resolve("brain.json");
        var brain = Files.isRegularFile(brainFile) ? JSON.readTree(brainFile.toFile())
                : JSON.createObjectNode().put("mode", "disabled");
        String brainMode = safe(brain.path("mode").asText("disabled"));
        if (!java.util.Set.of("disabled", "hermes", "openai-compatible").contains(brainMode)) {
            throw new IOException("Unsupported Brain mode");
        }
        String brainEndpoint = safe(brain.path("endpoint").asText("http://127.0.0.1:8080"));
        String brainEnv = safe(brain.path("tokenEnv").asText("MCAC_BRAIN_TOKEN"));
        environment(brainEnv, "Brain token");
        String brainModel = safe(brain.path("model").asText(
                "hermes".equals(brainMode) ? "hermes" : "disabled"));
        int brainTimeout = bounded(brain.path("timeoutSeconds").asInt(60), 1, 300,
                "Brain timeout must be 1..300 seconds");
        int brainOutput = bounded(brain.path("maxOutputTokens").asInt(1400), 128, 4096,
                "Brain output budget must be 128..4096");
        int brainTools = bounded(brain.path("maxToolCallsPerTurn").asInt(8), 1, 32,
                "Brain Tool budget must be 1..32");
        int brainRequests = bounded(brain.path("maxRequests").asInt(24), 1, 1000,
                "Brain request budget must be 1..1000");
        int brainInput = bounded(brain.path("maxInputTokens").asInt(30_000), 128, 2_000_000,
                "Brain input budget must be 128..2000000");
        int brainTotalOutput = bounded(brain.path("maxTotalOutputTokens").asInt(8_000), 128, 500_000,
                "Brain total output budget must be 128..500000");
        int brainMinutes = bounded(brain.path("maxWallClockMinutes").asInt(15), 1, 480,
                "Brain wall clock budget must be 1..480 minutes");
        int brainRetries = bounded(brain.path("maxRetries").asInt(2), 0, 5,
                "Brain retry budget must be 0..5");
        Path searchFile = profile.profileDirectory().resolve("search.json");
        var search = Files.isRegularFile(searchFile) ? JSON.readTree(searchFile.toFile())
                : JSON.createObjectNode().put("mode", "disabled");
        String searchMode = safe(search.path("mode").asText("disabled"));
        if (!java.util.Set.of("disabled", "http").contains(searchMode)) {
            throw new IOException("Unsupported Search mode");
        }
        String searchEndpoint = safe(search.path("endpoint").asText("https://search-provider.invalid/v1/search"));
        String searchEnv = safe(search.path("tokenEnv").asText("MC_COMPANION_SEARCH_TOKEN"));
        environment(searchEnv, "Search token");
        int searchTimeout = Math.max(1, Math.min(30, search.path("timeoutSeconds").asInt(15)));
        String yaml = "server:\n  bind: 127.0.0.1\n  port: " + profile.port()
                + "\n  management_port: " + profile.healthPort()
                + "\n  profile_id: \"" + safe(profile.instanceId()) + "\""
                + "\n  instance_id: \"" + safe(instance.instanceId()) + "\""
                + "\n  token_file: ./pairing.token\n  heartbeat_seconds: 15\n  allow_remote: false\n"
                + "database:\n  path: ./companion.db\nprovider:\n  mode: "+mode+"\n  base_url: \""+base+"\"\n  api_key_env: "+env+"\n  model: \""+model+"\"\n  timeout_seconds: "+providerTimeout+"\n"
                + "brain:\n  mode: " + brainMode
                + "\n  endpoint: \"" + brainEndpoint + "\"\n  token_env: " + brainEnv
                + "\n  model: \"" + brainModel + "\"\n  timeout_seconds: " + brainTimeout
                + "\n  max_output_tokens: " + brainOutput
                + "\n  max_tool_calls_per_turn: " + brainTools
                + "\n  max_requests: " + brainRequests
                + "\n  max_input_tokens: " + brainInput
                + "\n  max_total_output_tokens: " + brainTotalOutput
                + "\n  max_wall_clock_minutes: " + brainMinutes
                + "\n  max_retries: " + brainRetries + "\n"
                + "search:\n  mode: " + searchMode + "\n  endpoint: \"" + searchEndpoint
                + "\"\n  token_env: " + searchEnv + "\n  timeout_seconds: " + searchTimeout
                + "\n  allowed_domains: " + yamlList(search.path("allowedDomains"))
                + "\n  denied_domains: " + yamlList(search.path("deniedDomains")) + "\n"
                + "logging:\n  file: ./runtime.log\n  console: false\n";
        Files.writeString(profile.configFile(), yaml, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        RuntimeProfileService.writeIdentity(profile);
    }
    private static String safe(String value)throws IOException{if(value==null||value.contains("\n")||value.contains("\r")||value.contains("\"")||value.isBlank())throw new IOException("Unsafe provider configuration value");return value;}
    private static int bounded(int value, int minimum, int maximum, String message) throws IOException {
        if (value < minimum || value > maximum) throw new IOException(message);
        return value;
    }
    private static void environment(String value, String label) throws IOException {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            throw new IOException(label + " environment variable is invalid");
        }
    }
    private static String yamlList(com.fasterxml.jackson.databind.JsonNode values) throws IOException {
        if (!values.isArray() || values.isEmpty()) return "[]";
        java.util.List<String> entries = new java.util.ArrayList<>();
        for (var value : values) entries.add("\"" + safe(value.asText()) + "\"");
        return "[" + String.join(", ", entries) + "]";
    }

    /** Explicit rotation only. Both copies are restored if either write fails. */
    public void rotate(MinecraftInstance instance, RuntimeProfile profile) throws IOException {
        Path instanceToken = instance.gameDirectory().resolve(".mccompanion/runtime.token");
        Path profileToken = profile.profileDirectory().resolve("pairing.token");
        byte[] oldInstance = Files.exists(instanceToken) ? Files.readAllBytes(instanceToken) : null;
        byte[] oldProfile = Files.exists(profileToken) ? Files.readAllBytes(profileToken) : null;
        try {
            String token = newToken();
            writePrivateAtomic(instanceToken, token);
            writePrivateAtomic(profileToken, token);
        } catch (IOException failure) {
            restore(instanceToken, oldInstance); restore(profileToken, oldProfile); throw failure;
        }
    }
    public boolean tokensMatch(MinecraftInstance instance, RuntimeProfile profile) throws IOException {
        String left = readToken(instance.gameDirectory().resolve(".mccompanion/runtime.token"));
        String right = readToken(profile.profileDirectory().resolve("pairing.token"));
        return left != null && left.equals(right);
    }
    public Snapshot snapshot(MinecraftInstance instance, RuntimeProfile profile) throws IOException {
        Map<Path, byte[]> files = new LinkedHashMap<>();
        for (Path file : managedFiles(instance, profile)) {
            files.put(file, Files.exists(file) ? Files.readAllBytes(file) : null);
        }
        return new Snapshot(files);
    }
    public void restore(Snapshot snapshot) throws IOException {
        IOException failure = null;
        for (var entry : snapshot.files().entrySet()) {
            try { restore(entry.getKey(), entry.getValue()); }
            catch (IOException error) {
                if (failure == null) failure = new IOException("Unable to restore pairing transaction");
                failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
    private static java.util.List<Path> managedFiles(MinecraftInstance instance, RuntimeProfile profile) {
        return java.util.List.of(
                instance.gameDirectory().resolve(".mccompanion/runtime.token"),
                instance.configDirectory().resolve("minecraft-ai-companion/runtime.json"),
                profile.profileDirectory().resolve("pairing.token"),
                profile.configFile(),
                profile.profileDirectory().resolve("profile.json"));
    }
    private static String newToken() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String readToken(Path file) throws IOException { if (!Files.isRegularFile(file)) return null; OwnerOnlyFile.secure(file); String value=Files.readString(file,StandardCharsets.US_ASCII).trim(); return value.isBlank()?null:value; }
    private static void restore(Path file, byte[] value) throws IOException { if(value==null) Files.deleteIfExists(file); else { Files.write(file,value); if (file.getFileName().toString().endsWith(".token")) OwnerOnlyFile.secure(file); } }
    private static void writePrivateAtomic(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = OwnerOnlyFile.createTempFile(file.getParent(), ".mcac-token-", ".tmp");
        try {
            Files.writeString(temporary, value + System.lineSeparator(), StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(java.nio.file.AtomicMoveNotSupportedException ignored){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
            OwnerOnlyFile.secure(file);
        } finally { Files.deleteIfExists(temporary); }
    }
    public record Snapshot(Map<Path, byte[]> files) {
        public Snapshot {
            Map<Path, byte[]> copy = new LinkedHashMap<>();
            files.forEach((path, bytes) -> copy.put(path, bytes == null ? null : bytes.clone()));
            files = java.util.Collections.unmodifiableMap(copy);
        }
    }
}
