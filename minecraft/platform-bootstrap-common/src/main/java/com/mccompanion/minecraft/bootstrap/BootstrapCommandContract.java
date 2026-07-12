package com.mccompanion.minecraft.bootstrap;

import java.util.List;

/** Shared command names and honest bootstrap failure messages for every loader. */
public final class BootstrapCommandContract {
    public static final String ROOT = "companion";
    public static final List<String> AVAILABLE_COMMANDS = List.of("status", "capabilities", "help");
    public static final List<String> UNAVAILABLE_COMMANDS =
            List.of("create", "spawn", "despawn", "remove", "follow", "goto", "stop", "pause", "resume");

    private BootstrapCommandContract() {
    }

    public static String unavailableMessage(String command) {
        return "NOT_IMPLEMENTED_ALPHA: /" + ROOT + ' ' + command
                + " is registered for consistent discovery, but the body/behavior service is not wired; no world state changed.";
    }

    public static String helpText() {
        return "/companion status | capabilities | help; reserved bootstrap commands: "
                + String.join(", ", UNAVAILABLE_COMMANDS);
    }
}
