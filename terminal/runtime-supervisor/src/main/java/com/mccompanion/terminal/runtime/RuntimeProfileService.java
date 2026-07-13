package com.mccompanion.terminal.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Allocates and persists one stable profile/port per instance. */
public final class RuntimeProfileService {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int FIRST_PORT=8766, LAST_PORT=8866;
    private final Path profilesHome;
    private final Path launcher;
    public RuntimeProfileService(Path controlHome, Path launcher){this.profilesHome=controlHome.resolve("profiles");this.launcher=launcher;}
    public synchronized RuntimeProfile ensure(String instanceId) throws IOException {
        Path directory=profilesHome.resolve(instanceId).normalize();
        if(!directory.startsWith(profilesHome.normalize()))throw new IOException("Unsafe instance id");
        Path identity=directory.resolve("profile.json");
        if(Files.isRegularFile(identity)){
            var node=JSON.readTree(identity.toFile());
            if(!instanceId.equals(node.path("instanceId").asText()))throw new IOException("Runtime profile identity mismatch");
            int port=node.path("port").asInt(); if(port<FIRST_PORT||port>LAST_PORT)throw new IOException("Runtime profile port is invalid");
            return new RuntimeProfile(instanceId,directory,launcher,port);
        }
        Files.createDirectories(directory);
        Set<Integer> used=new HashSet<>();
        if(Files.isDirectory(profilesHome))try(var dirs=Files.newDirectoryStream(profilesHome,Files::isDirectory)){for(Path dir:dirs){Path file=dir.resolve("profile.json");if(Files.isRegularFile(file))try{used.add(JSON.readTree(file.toFile()).path("port").asInt());}catch(IOException ignored){}}}
        int port=java.util.stream.IntStream.rangeClosed(FIRST_PORT,LAST_PORT).filter(p->!used.contains(p)).findFirst().orElseThrow(()->new IOException("No free Runtime profile port"));
        RuntimeProfile profile=new RuntimeProfile(instanceId,directory,launcher,port); writeIdentity(profile); return profile;
    }
    static void writeIdentity(RuntimeProfile profile)throws IOException{Files.createDirectories(profile.profileDirectory());ObjectNode node=JSON.createObjectNode().put("schemaVersion",1).put("instanceId",profile.instanceId()).put("port",profile.port());JSON.writerWithDefaultPrettyPrinter().writeValue(profile.profileDirectory().resolve("profile.json").toFile(),node);}
}
