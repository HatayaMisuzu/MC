package com.mccompanion.terminal.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Allocates and persists one stable profile/port per instance. */
public final class RuntimeProfileService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final ConcurrentHashMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();
    public static final int FIRST_PORT=8766, LAST_PORT=8866;
    private final Path profilesHome;
    private final Path launcher;
    public RuntimeProfileService(Path controlHome, Path launcher){this.profilesHome=controlHome.resolve("profiles");this.launcher=launcher;}
    public RuntimeProfile ensure(String instanceId) throws IOException {
        if (instanceId == null || !SAFE_ID.matcher(instanceId).matches()) {
            throw new IOException("Unsafe instance id");
        }
        Files.createDirectories(profilesHome);
        Path lockPath = profilesHome.resolve(".allocation.lock");
        Object jvmLock = JVM_LOCKS.computeIfAbsent(profilesHome.toAbsolutePath().normalize(), ignored -> new Object());
        synchronized (jvmLock) {
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return ensureLocked(instanceId);
            }
        }
    }

    private RuntimeProfile ensureLocked(String instanceId) throws IOException {
        Path directory=profilesHome.resolve(instanceId).normalize();
        Path identity=directory.resolve("profile.json");
        if(Files.isRegularFile(identity)){
            var node=JSON.readTree(identity.toFile());
            if(!instanceId.equals(node.path("instanceId").asText()))throw new IOException("Runtime profile identity mismatch");
            int port=node.path("port").asInt();
            int healthPort=node.path("healthPort").asInt(port + 10_000);
            if(port<FIRST_PORT||port>LAST_PORT||healthPort != port + 10_000)throw new IOException("Runtime profile port is invalid");
            return new RuntimeProfile(instanceId,directory,launcher,port,healthPort);
        }
        Set<Integer> used=new HashSet<>();
        if(Files.isDirectory(profilesHome))try(var dirs=Files.newDirectoryStream(profilesHome,Files::isDirectory)){for(Path dir:dirs){Path file=dir.resolve("profile.json");if(Files.isRegularFile(file))try{used.add(JSON.readTree(file.toFile()).path("port").asInt());}catch(IOException ignored){}}}
        int port=java.util.stream.IntStream.rangeClosed(FIRST_PORT,LAST_PORT)
                .filter(p->!used.contains(p) && available(p) && available(p + 10_000))
                .findFirst().orElseThrow(()->new IOException("No free Runtime profile port"));
        RuntimeProfile profile=new RuntimeProfile(instanceId,directory,launcher,port,port + 10_000);
        writeIdentity(profile);
        return profile;
    }

    private static boolean available(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException occupied) {
            return false;
        }
    }

    static void writeIdentity(RuntimeProfile profile)throws IOException{
        Files.createDirectories(profile.profileDirectory());
        ObjectNode node=JSON.createObjectNode().put("schemaVersion",2).put("instanceId",profile.instanceId())
                .put("port",profile.port()).put("healthPort", profile.healthPort());
        Path destination = profile.profileDirectory().resolve("profile.json");
        Path temporary = Files.createTempFile(profile.profileDirectory(), ".profile-", ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),node);
        try {
            Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
