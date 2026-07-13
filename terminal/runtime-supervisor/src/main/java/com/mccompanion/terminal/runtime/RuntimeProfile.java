package com.mccompanion.terminal.runtime;

import java.nio.file.Path;

public record RuntimeProfile(
        String instanceId,
        Path profileDirectory,
        Path launcherScript,
        int port,
        int healthPort) {

    public RuntimeProfile(String instanceId, Path profileDirectory, Path launcherScript, int port) {
        this(instanceId, profileDirectory, launcherScript, port, port + 10_000);
    }

    public Path configFile() { return profileDirectory.resolve("runtime.yml"); }
    public Path pidFile() { return profileDirectory.resolve("runtime.pid"); }
    public Path logFile() { return profileDirectory.resolve("runtime-process.log"); }
}
