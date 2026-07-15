package com.mccompanion.runtime.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigTest {
    @TempDir Path temporary;

    @Test
    void writesSafeRulesDefaultsWithoutSecretValue() throws Exception {
        Path configPath = temporary.resolve("config/runtime.yml");
        RuntimeConfig config = RuntimeConfig.load(configPath);
        assertEquals("127.0.0.1", config.server.bind);
        assertEquals("rules", config.provider.mode);
        assertEquals("disabled", config.brain.mode);
        assertEquals("https://api.deepseek.com", config.provider.baseUrl);
        assertEquals("deepseek-v4-flash", config.provider.model);
        assertTrue(config.tokenPath().startsWith(configPath.getParent()));
        String yaml = Files.readString(configPath);
        assertTrue(yaml.contains("api_key_env: MC_COMPANION_API_KEY"));
        assertTrue(yaml.contains("token_env: MC_COMPANION_BRAIN_TOKEN"));
        assertFalse(yaml.matches("(?s).*api[_-]?key\\s*:\\s*sk-.*"));
        assertFalse(yaml.matches("(?s).*token\\s*:\\s*[^#\\s].*"));
    }

    @Test
    void rejectsRemoteBindAndUnknownFields() throws Exception {
        RuntimeConfig remote = RuntimeConfig.defaults(temporary);
        remote.server.bind = "8.8.8.8";
        assertThrows(IllegalArgumentException.class, remote::normalizeAndValidate);

        Path invalid = temporary.resolve("unknown.yml");
        Files.writeString(invalid, RuntimeConfig.defaultYaml() + "unknown_field: true\n");
        assertThrows(Exception.class, () -> RuntimeConfig.load(invalid));
    }

    @Test
    void acceptsEphemeralLoopbackPort() {
        RuntimeConfig config = RuntimeConfig.defaults(temporary);
        config.server.port = 0;
        assertDoesNotThrow(config::normalizeAndValidate);
    }

    @Test
    void rejectsUnknownBrainModeAndUnsafeToolBudget() {
        RuntimeConfig config = RuntimeConfig.defaults(temporary);
        config.brain.mode = "internal-agent";
        assertThrows(IllegalArgumentException.class, config::normalizeAndValidate);

        config.brain.mode = "disabled";
        config.brain.maxToolCallsPerTurn = 33;
        assertThrows(IllegalArgumentException.class, config::normalizeAndValidate);
    }
}
