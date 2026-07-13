package com.mccompanion.terminal.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class WindowsRuntimeSupervisor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROTOCOL = "mc-companion/1";

    public Process start(RuntimeProfile profile) throws IOException {
        Optional<Long> active = activePid(profile);
        if (active.isPresent()) {
            RuntimeHealth health=status(profile);
            if(!health.identityMatches())throw new IOException("PID belongs to a different process");
            return null;
        }
        if(portOnline(profile))throw new IOException("Runtime profile port is occupied by another process");
        Files.createDirectories(profile.profileDirectory());
        Process process = new ProcessBuilder(profile.launcherScript().toString(), "--config", profile.configFile().toString(), "--no-cli")
                .directory(profile.profileDirectory().toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(profile.logFile().toFile())).start();
        Files.writeString(profile.pidFile(), Long.toString(process.pid()), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        HealthIdentity identity = awaitStartingIdentity(profile, process, Duration.ofSeconds(15));
        if (identity != null) {
            Files.writeString(profile.pidFile(), Long.toString(identity.pid()), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        RuntimeHealth health = awaitHealthy(profile, Duration.ofSeconds(2));
        if (!health.healthy()) {
            process.descendants().forEach(child -> child.destroyForcibly());
            process.destroyForcibly();
            Files.deleteIfExists(profile.pidFile());
            throw new IOException("Runtime did not report matching authenticated health: " + health.detail());
        }
        return process;
    }

    private HealthIdentity awaitStartingIdentity(RuntimeProfile profile, Process wrapper, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        do {
            HealthIdentity identity = requestHealth(profile).orElse(null);
            if (identity != null && profileIdentityMatches(profile, identity)
                    && isChildRuntime(wrapper, identity.pid())) return identity;
            try { Thread.sleep(100); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (Instant.now().isBefore(deadline));
        return null;
    }

    private static boolean profileIdentityMatches(RuntimeProfile profile, HealthIdentity identity) {
        return identity.profileId().equals(profile.instanceId())
                && identity.instanceId().equals(profile.instanceId())
                && identity.port() == profile.port()
                && identity.managementPort() == profile.healthPort()
                && PROTOCOL.equals(identity.protocolVersion());
    }

    private static boolean isChildRuntime(Process wrapper, long pid) {
        if (pid <= 0) return false;
        if (wrapper.descendants().anyMatch(child -> child.pid() == pid)) return true;
        return ProcessHandle.of(pid)
                .flatMap(ProcessHandle::parent)
                .map(parent -> parent.pid() == wrapper.pid())
                .orElse(false);
    }
    public RuntimeHealth status(RuntimeProfile profile){
        Optional<Long> pid=activePid(profile);
        boolean processIdentity=false;
        if(pid.isPresent())processIdentity=ProcessHandle.of(pid.get()).flatMap(h->h.info().command()).map(command->{String expected=profile.launcherScript().getFileName().toString().toLowerCase();String actual=java.nio.file.Path.of(command).getFileName().toString().toLowerCase();return actual.equals(expected)||actual.contains("java")&&expected.endsWith(".bat");}).orElse(false);
        boolean online=portOnline(profile);
        HealthIdentity identity = requestHealth(profile).orElse(null);
        boolean identityMatches = identity != null && profileIdentityMatches(profile, identity)
                && pid.isPresent() && identity.pid() == pid.get();
        boolean healthy=pid.isPresent()&&processIdentity&&online&&identityMatches;
        String detail = healthy ? "authenticated health identity verified"
                : identity == null ? "authenticated health endpoint unavailable"
                : "health identity does not match profile or PID";
        return new RuntimeHealth(pid.orElse(null),pid.isPresent(),processIdentity,online,identityMatches,
                identity == null ? null : identity.runtimeVersion(),
                identity == null ? null : identity.protocolVersion(),
                identity == null ? 0 : identity.sessionCount(), healthy, detail);
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

    public RuntimeHealth awaitHealthy(RuntimeProfile profile, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        RuntimeHealth health;
        do {
            health = status(profile);
            if (health.healthy()) return health;
            try { Thread.sleep(100); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return health; }
        } while (Instant.now().isBefore(deadline));
        return health;
    }

    private Optional<HealthIdentity> requestHealth(RuntimeProfile profile) {
        try {
            Path tokenFile = profile.profileDirectory().resolve("pairing.token");
            if (!Files.isRegularFile(tokenFile)) return Optional.empty();
            String token = Files.readString(tokenFile, StandardCharsets.US_ASCII).trim();
            if (token.isEmpty()) return Optional.empty();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                            + profile.healthPort() + "/health"))
                    .timeout(Duration.ofMillis(700)).header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return Optional.empty();
            JsonNode node = JSON.readTree(response.body());
            return Optional.of(new HealthIdentity(node.path("runtimeVersion").asText(""),
                    node.path("protocolVersion").asText(""), node.path("profileId").asText(""),
                    node.path("instanceId").asText(""), node.path("port").asInt(-1),
                    node.path("managementPort").asInt(-1), node.path("pid").asLong(-1),
                    node.path("sessionCount").asInt(-1)));
        } catch (Exception unavailable) {
            return Optional.empty();
        }
    }
    public void stop(RuntimeProfile profile) throws IOException {
        Optional<Long> pid = activePid(profile);
        if (pid.isEmpty()) { Files.deleteIfExists(profile.pidFile()); return; }
        RuntimeHealth health = status(profile);
        if (!health.processIdentityMatches()) {
            Files.deleteIfExists(profile.pidFile());
            throw new IOException("Refusing to stop stale PID because process identity does not match Runtime");
        }
        ProcessHandle handle = ProcessHandle.of(pid.get()).orElseThrow();
        handle.destroy();
        try { handle.onExit().get(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (Exception timeout) { handle.destroyForcibly(); }
        Files.deleteIfExists(profile.pidFile());
    }
    private record HealthIdentity(String runtimeVersion,String protocolVersion,String profileId,String instanceId,
                                  int port,int managementPort,long pid,int sessionCount){}
    public record RuntimeHealth(Long pid,boolean pidAlive,boolean processIdentityMatches,boolean portOnline,
                                boolean identityMatches,String runtimeVersion,String protocolVersion,
                                int sessionCount,boolean healthy,String detail){}
}
