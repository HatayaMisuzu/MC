package com.mccompanion.terminal.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;

public final class WindowsRuntimeSupervisor {
    public Process start(RuntimeProfile profile) throws IOException {
        Optional<Long> active = activePid(profile);
        if (active.isPresent()) return null;
        Files.createDirectories(profile.profileDirectory());
        Process process = new ProcessBuilder(profile.launcherScript().toString(), "--config", profile.configFile().toString(), "--no-cli")
                .directory(profile.profileDirectory().toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(profile.logFile().toFile())).start();
        Files.writeString(profile.pidFile(), Long.toString(process.pid()), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return process;
    }
    public Optional<Long> activePid(RuntimeProfile profile) {
        try {
            if (!Files.isRegularFile(profile.pidFile())) return Optional.empty();
            long pid = Long.parseLong(Files.readString(profile.pidFile()).strip());
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).map(ProcessHandle::pid);
        } catch (IOException | NumberFormatException ignored) { return Optional.empty(); }
    }
    public boolean portOnline(RuntimeProfile profile) {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", profile.port()), 300); return true; }
        catch (IOException ignored) { return false; }
    }
    public void stop(RuntimeProfile profile) throws IOException {
        Optional<Long> pid = activePid(profile);
        if (pid.isEmpty()) { Files.deleteIfExists(profile.pidFile()); return; }
        ProcessHandle handle = ProcessHandle.of(pid.get()).orElseThrow();
        handle.destroy();
        try { handle.onExit().get(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (Exception timeout) { handle.destroyForcibly(); }
        Files.deleteIfExists(profile.pidFile());
    }
}
