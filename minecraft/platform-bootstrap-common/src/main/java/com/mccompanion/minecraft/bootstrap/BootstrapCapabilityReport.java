package com.mccompanion.minecraft.bootstrap;

import java.util.List;
import java.util.Objects;

/**
 * Loader-neutral description of what the bootstrap can actually provide.
 *
 * <p>The initial platform bootstrap intentionally reports unavailable body and behavior services instead
 * of pretending that command registration means those services are implemented. Loader entrypoints log
 * this report during startup and expose it through {@code /companion capabilities}.</p>
 */
public record BootstrapCapabilityReport(
        String modVersion,
        String minecraftVersion,
        String loader,
        String loaderBuildVersion,
        int javaTarget,
        String runtimeState,
        boolean runtimeRequiredForLoad,
        String bodyState,
        String behaviorState,
        String persistenceState,
        String worldMutationState,
        List<String> availableCommands,
        List<String> unavailableCommands,
        List<String> optionalAdapters) {

    public BootstrapCapabilityReport {
        modVersion = requireText(modVersion, "modVersion");
        minecraftVersion = requireText(minecraftVersion, "minecraftVersion");
        loader = requireText(loader, "loader");
        loaderBuildVersion = requireText(loaderBuildVersion, "loaderBuildVersion");
        runtimeState = requireText(runtimeState, "runtimeState");
        bodyState = requireText(bodyState, "bodyState");
        behaviorState = requireText(behaviorState, "behaviorState");
        persistenceState = requireText(persistenceState, "persistenceState");
        worldMutationState = requireText(worldMutationState, "worldMutationState");
        availableCommands = List.copyOf(Objects.requireNonNull(availableCommands, "availableCommands"));
        unavailableCommands = List.copyOf(Objects.requireNonNull(unavailableCommands, "unavailableCommands"));
        optionalAdapters = List.copyOf(Objects.requireNonNull(optionalAdapters, "optionalAdapters"));
    }

    public static BootstrapCapabilityReport minimal(
            String modVersion,
            String minecraftVersion,
            String loader,
            String loaderBuildVersion,
            int javaTarget) {
        return new BootstrapCapabilityReport(
                modVersion,
                minecraftVersion,
                loader,
                loaderBuildVersion,
                javaTarget,
                "OFFLINE_SAFE_IDLE",
                false,
                "NOT_WIRED",
                "NOT_WIRED",
                "NOT_WIRED",
                "DISABLED",
                BootstrapCommandContract.AVAILABLE_COMMANDS,
                BootstrapCommandContract.UNAVAILABLE_COMMANDS,
                List.of());
    }

    public String statusLine() {
        return "Minecraft AI Companion " + modVersion
                + " platform=" + loader + "/" + minecraftVersion
                + " state=SAFE_IDLE runtime=OFFLINE body=" + bodyState
                + " behavior=" + behaviorState
                + " worldMutation=" + worldMutationState;
    }

    public String toJson() {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        appendString(json, "modVersion", modVersion).append(',');
        appendString(json, "minecraftVersion", minecraftVersion).append(',');
        appendString(json, "loader", loader).append(',');
        appendString(json, "loaderBuildVersion", loaderBuildVersion).append(',');
        json.append("\"javaTarget\":").append(javaTarget).append(',');
        appendString(json, "runtimeState", runtimeState).append(',');
        json.append("\"runtimeRequiredForLoad\":").append(runtimeRequiredForLoad).append(',');
        appendString(json, "bodyState", bodyState).append(',');
        appendString(json, "behaviorState", behaviorState).append(',');
        appendString(json, "persistenceState", persistenceState).append(',');
        appendString(json, "worldMutationState", worldMutationState).append(',');
        appendList(json, "availableCommands", availableCommands).append(',');
        appendList(json, "unavailableCommands", unavailableCommands).append(',');
        appendList(json, "optionalAdapters", optionalAdapters);
        return json.append('}').toString();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static StringBuilder appendString(StringBuilder json, String key, String value) {
        return json.append('"').append(escape(key)).append("\":\"")
                .append(escape(value)).append('"');
    }

    private static StringBuilder appendList(StringBuilder json, String key, List<String> values) {
        json.append('"').append(escape(key)).append("\":[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(escape(values.get(index))).append('"');
        }
        return json.append(']');
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
