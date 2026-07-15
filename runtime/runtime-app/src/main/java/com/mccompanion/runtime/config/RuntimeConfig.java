package com.mccompanion.runtime.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@JsonIgnoreProperties(ignoreUnknown = false)
public final class RuntimeConfig {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    public Server server = new Server();
    public Database database = new Database();
    public Provider provider = new Provider();
    public Brain brain = new Brain();
    public Logging logging = new Logging();

    @JsonIgnore
    private Path baseDirectory = Path.of(".").toAbsolutePath().normalize();

    public static RuntimeConfig load(Path configPath) throws IOException {
        Path absolute = configPath.toAbsolutePath().normalize();
        Path parent = Optional.ofNullable(absolute.getParent()).orElse(Path.of(".").toAbsolutePath());
        if (Files.notExists(absolute)) {
            Files.createDirectories(parent);
            Files.writeString(absolute, defaultYaml(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        RuntimeConfig config = YAML.readValue(Files.readString(absolute, StandardCharsets.UTF_8), RuntimeConfig.class);
        config.baseDirectory = parent;
        config.normalizeAndValidate();
        return config;
    }

    public static RuntimeConfig defaults(Path baseDirectory) {
        RuntimeConfig config = new RuntimeConfig();
        config.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        config.normalizeAndValidate();
        return config;
    }

    public void normalizeAndValidate() {
        server = Objects.requireNonNullElseGet(server, Server::new);
        database = Objects.requireNonNullElseGet(database, Database::new);
        provider = Objects.requireNonNullElseGet(provider, Provider::new);
        brain = Objects.requireNonNullElseGet(brain, Brain::new);
        logging = Objects.requireNonNullElseGet(logging, Logging::new);
        server.bind = requireText(server.bind, "server.bind");
        if (server.port < 0 || server.port > 65_535) {
            throw new IllegalArgumentException("server.port must be between 0 and 65535");
        }
        if (server.managementPort < 1 || server.managementPort > 65_535 || server.managementPort == server.port) {
            throw new IllegalArgumentException("server.management_port must be a distinct port between 1 and 65535");
        }
        server.profileId = requireText(server.profileId, "server.profile_id");
        server.instanceId = requireText(server.instanceId, "server.instance_id");
        if (server.heartbeatSeconds < 2 || server.heartbeatSeconds > 300) {
            throw new IllegalArgumentException("server.heartbeat_seconds must be between 2 and 300");
        }
        try {
            if (!InetAddress.getByName(server.bind).isLoopbackAddress()) {
                throw new IllegalArgumentException("Alpha 0.1 only supports loopback server.bind addresses");
            }
            if (server.allowRemote) {
                throw new IllegalArgumentException("server.allow_remote is not supported without TLS in Alpha 0.1");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("server.bind cannot be resolved", exception);
        }
        database.path = requireText(database.path, "database.path");
        server.tokenFile = requireText(server.tokenFile, "server.token_file");
        logging.file = requireText(logging.file, "logging.file");
        provider.mode = requireText(provider.mode, "provider.mode").toLowerCase(Locale.ROOT);
        if (!provider.mode.equals("rules") && !provider.mode.equals("auto") && !provider.mode.equals("openai-compatible")) {
            throw new IllegalArgumentException("provider.mode must be rules, auto, or openai-compatible");
        }
        if (provider.timeoutSeconds < 1 || provider.timeoutSeconds > 300) {
            throw new IllegalArgumentException("provider.timeout_seconds must be between 1 and 300");
        }
        if (provider.maxOutputTokens < 128 || provider.maxOutputTokens > 4096) throw new IllegalArgumentException("provider.max_output_tokens must be 128..4096");
        if (provider.maxCallsPerMinute < 1 || provider.maxCallsPerMinute > 600) throw new IllegalArgumentException("provider.max_calls_per_minute must be 1..600");
        if (provider.maxConcurrent < 1 || provider.maxConcurrent > 16) throw new IllegalArgumentException("provider.max_concurrent must be 1..16");
        if (provider.maxRetries < 0 || provider.maxRetries > 3) throw new IllegalArgumentException("provider.max_retries must be 0..3");
        provider.apiKeyEnv = requireText(provider.apiKeyEnv, "provider.api_key_env");
        if (!ENVIRONMENT_NAME.matcher(provider.apiKeyEnv).matches()) {
            throw new IllegalArgumentException("provider.api_key_env must be a valid environment variable name");
        }
        if (!provider.mode.equals("rules")) {
            provider.baseUrl = requireText(provider.baseUrl, "provider.base_url");
            provider.model = requireText(provider.model, "provider.model");
        }
        brain.mode = requireText(brain.mode, "brain.mode").toLowerCase(Locale.ROOT);
        if (!brain.mode.equals("disabled") && !brain.mode.equals("hermes")
                && !brain.mode.equals("openai-compatible")) {
            throw new IllegalArgumentException("brain.mode must be disabled, hermes, or openai-compatible");
        }
        if (brain.maxToolCallsPerTurn < 1 || brain.maxToolCallsPerTurn > 32) {
            throw new IllegalArgumentException("brain.max_tool_calls_per_turn must be 1..32");
        }
        if (brain.timeoutSeconds < 1 || brain.timeoutSeconds > 300) {
            throw new IllegalArgumentException("brain.timeout_seconds must be 1..300");
        }
        if (brain.maxOutputTokens < 128 || brain.maxOutputTokens > 4096) {
            throw new IllegalArgumentException("brain.max_output_tokens must be 128..4096");
        }
        brain.tokenEnv = requireText(brain.tokenEnv, "brain.token_env");
        if (!ENVIRONMENT_NAME.matcher(brain.tokenEnv).matches()) {
            throw new IllegalArgumentException("brain.token_env must be a valid environment variable name");
        }
        if (!brain.mode.equals("disabled")) brain.endpoint = requireText(brain.endpoint, "brain.endpoint");
        if (brain.mode.equals("openai-compatible")) brain.model = requireText(brain.model, "brain.model");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public Path resolve(String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : baseDirectory.resolve(path)).normalize().toAbsolutePath();
    }

    public Path tokenPath() {
        return resolve(server.tokenFile);
    }

    public Path databasePath() {
        return resolve(database.path);
    }

    public Path logPath() {
        return resolve(logging.file);
    }

    public static String defaultYaml() {
        return """
                # Minecraft AI Companion Runtime 0.1
                server:
                  bind: 127.0.0.1
                  port: 8766
                  management_port: 18766
                  profile_id: default
                  instance_id: default
                  token_file: ./data/pairing.token
                  heartbeat_seconds: 15
                  allow_remote: false

                database:
                  path: ./data/companion.db

                # rules requires no model or API key.
                provider:
                  mode: rules
                  base_url: https://api.deepseek.com
                  api_key_env: MC_COMPANION_API_KEY
                  model: deepseek-v4-flash
                  timeout_seconds: 60
                  max_output_tokens: 1400
                  max_calls_per_minute: 30
                  max_concurrent: 2
                  max_retries: 2

                # External Brain First. Configure a token only through the named environment variable.
                brain:
                  mode: disabled
                  endpoint: http://127.0.0.1:18888
                  token_env: MC_COMPANION_BRAIN_TOKEN
                  model: deepseek-v4-flash
                  timeout_seconds: 60
                  max_output_tokens: 1400
                  max_tool_calls_per_turn: 8

                logging:
                  file: ./logs/runtime.log
                  console: true
                """;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class Server {
        public String bind = "127.0.0.1";
        public int port = 8766;
        @JsonProperty("management_port")
        public int managementPort = 18766;
        @JsonProperty("profile_id")
        public String profileId = "default";
        @JsonProperty("instance_id")
        public String instanceId = "default";
        @JsonProperty("token_file")
        public String tokenFile = "./data/pairing.token";
        @JsonProperty("heartbeat_seconds")
        public int heartbeatSeconds = 15;
        @JsonProperty("allow_remote")
        public boolean allowRemote;

        @JsonIgnore
        public Duration heartbeatInterval() {
            return Duration.ofSeconds(heartbeatSeconds);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class Database {
        public String path = "./data/companion.db";
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class Provider {
        public String mode = "rules";
        @JsonProperty("base_url")
        public String baseUrl = "https://api.deepseek.com";
        @JsonProperty("api_key_env")
        public String apiKeyEnv = "MC_COMPANION_API_KEY";
        public String model = "deepseek-v4-flash";
        @JsonProperty("timeout_seconds")
        public int timeoutSeconds = 60;
        @JsonProperty("max_output_tokens")
        public int maxOutputTokens = 1400;
        @JsonProperty("max_calls_per_minute")
        public int maxCallsPerMinute = 30;
        @JsonProperty("max_concurrent")
        public int maxConcurrent = 2;
        @JsonProperty("max_retries")
        public int maxRetries = 2;

        @JsonIgnore
        public Optional<String> resolveApiKey() {
            if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
                return Optional.empty();
            }
            String value = System.getenv(apiKeyEnv.trim());
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
        }

        @JsonIgnore
        public Duration timeout() {
            return Duration.ofSeconds(timeoutSeconds);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class Logging {
        public String file = "./logs/runtime.log";
        public boolean console = true;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class Brain {
        public String mode = "disabled";
        public String endpoint = "http://127.0.0.1:18888";
        @JsonProperty("token_env")
        public String tokenEnv = "MC_COMPANION_BRAIN_TOKEN";
        public String model = "deepseek-v4-flash";
        @JsonProperty("timeout_seconds")
        public int timeoutSeconds = 60;
        @JsonProperty("max_output_tokens")
        public int maxOutputTokens = 1400;
        @JsonProperty("max_tool_calls_per_turn")
        public int maxToolCallsPerTurn = 8;

        @JsonIgnore public Optional<String> resolveToken() {
            String value = System.getenv(tokenEnv);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
        }
        @JsonIgnore public Duration timeout() { return Duration.ofSeconds(timeoutSeconds); }
    }
}
