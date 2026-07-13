package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.terminal.launcher.LauncherInstallation;
import com.mccompanion.terminal.launcher.LauncherType;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Explicit, backed-up launcher hook mutations. Generated wrappers always let Minecraft continue. */
final class HookService {
    private static final ObjectMapper JSON = new ObjectMapper();

    void install(MinecraftInstance instance, LauncherInstallation launcher, Path mcac, Path controlHome) throws IOException {
        ensureLauncherStopped(launcher);
        Path state = controlHome.resolve("hooks").resolve(instance.instanceId());
        Files.createDirectories(state);
        Path wrapper = state.resolve(launcher.type() == LauncherType.PCL2 ? "pcl-prelaunch.cmd" : "hmcl-prelaunch.cmd");
        if (launcher.type() == LauncherType.PCL2) installPcl(instance, launcher, mcac, state, wrapper);
        else if (launcher.type() == LauncherType.HMCL) installHmcl(instance, launcher, mcac, state, wrapper);
        else throw new IOException("Hooks are unavailable for generic launchers");
    }

    void remove(MinecraftInstance instance, LauncherInstallation launcher, Path controlHome) throws IOException {
        ensureLauncherStopped(launcher);
        Path state = controlHome.resolve("hooks").resolve(instance.instanceId());
        Path metadata = state.resolve("state.json");
        if (!Files.isRegularFile(metadata)) throw new IOException("No managed hook is installed");
        JsonNode node = JSON.readTree(metadata.toFile());
        Path target = Path.of(node.path("target").asText()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(target) || !sha256(target).equals(node.path("modifiedSha256").asText()))
            throw new IOException("Launcher hook config changed after install; refusing to overwrite user changes");
        Path backup = state.resolve("original.backup");
        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(state.resolve("pcl-prelaunch.cmd"));
        Files.deleteIfExists(state.resolve("hmcl-prelaunch.cmd"));
        Files.deleteIfExists(metadata);
    }

    String status(MinecraftInstance instance, Path controlHome) {
        return Files.isRegularFile(controlHome.resolve("hooks").resolve(instance.instanceId()).resolve("state.json")) ? "INSTALLED" : "NOT_INSTALLED";
    }

    private static void installPcl(MinecraftInstance instance, LauncherInstallation launcher, Path mcac, Path state, Path wrapper) throws IOException {
        Path target = instance.versionDirectory().resolve("PCL").resolve("Setup.ini");
        Files.createDirectories(target.getParent());
        List<String> lines = Files.isRegularFile(target) ? Files.readAllLines(target, StandardCharsets.UTF_8) : new ArrayList<>();
        String existing = value(lines, "VersionAdvanceRun");
        writeWrapper(wrapper, existing, mcac, launcher.executable().getParent(), instance.instanceId());
        replace(lines, "VersionAdvanceRun", wrapper.toString());
        replace(lines, "VersionAdvanceRunWait", "False");
        backupAndWrite(target, String.join("\r\n", lines) + "\r\n", state, LauncherType.PCL2);
    }

    private static void installHmcl(MinecraftInstance instance, LauncherInstallation launcher, Path mcac, Path state, Path wrapper) throws IOException {
        Path target = launcher.dataDirectory().resolve("hmcl.json");
        ObjectNode document = (ObjectNode) JSON.readTree(target.toFile());
        ObjectNode selected = null;
        var fields = document.path("configurations").fields();
        while (fields.hasNext()) {
            var entry = fields.next(); JsonNode config = entry.getValue();
            String version = config.path("selectedMinecraftVersion").asText("");
            if (version.equals(instance.displayName()) && config instanceof ObjectNode object) { selected = object; break; }
        }
        if (selected == null) throw new IOException("HMCL configuration for this selected version is ambiguous; use guided play mode");
        String existing = selected.path("preLaunchCommand").asText(selected.path("precalledCommand").asText(""));
        writeWrapper(wrapper, existing, mcac, launcher.executable().getParent(), instance.instanceId());
        selected.put("preLaunchCommand", wrapper.toString());
        Path temporary = state.resolve("hmcl.modified.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), document);
        JSON.readTree(temporary.toFile());
        backupAndMove(target, temporary, state, LauncherType.HMCL);
    }

    private static void backupAndWrite(Path target, String content, Path state, LauncherType type) throws IOException {
        if (Files.isRegularFile(target)) Files.copy(target, state.resolve("original.backup"), StandardCopyOption.REPLACE_EXISTING);
        else Files.writeString(state.resolve("original.backup"), "", StandardCharsets.UTF_8);
        Path temp = state.resolve("modified.tmp"); Files.writeString(temp, content, StandardCharsets.UTF_8);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        writeState(target, state, type);
    }
    private static void backupAndMove(Path target, Path temporary, Path state, LauncherType type) throws IOException {
        Files.copy(target, state.resolve("original.backup"), StandardCopyOption.REPLACE_EXISTING);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        writeState(target, state, type);
    }
    private static void writeState(Path target, Path state, LauncherType type) throws IOException {
        ObjectNode metadata = JSON.createObjectNode().put("schemaVersion",1).put("launcher",type.name())
                .put("target",target.toAbsolutePath().normalize().toString()).put("modifiedSha256",sha256(target));
        JSON.writerWithDefaultPrettyPrinter().writeValue(state.resolve("state.json").toFile(), metadata);
    }
    private static void writeWrapper(Path wrapper, String existing, Path mcac, Path launcherRoot, String instanceId) throws IOException {
        StringBuilder text = new StringBuilder("@echo off\r\n");
        if (existing != null && !existing.isBlank()) text.append("call ").append(existing).append(" 2>nul\r\n");
        text.append("start \"\" /b \"").append(mcac.toAbsolutePath().normalize()).append("\" --root \"")
                .append(launcherRoot.toAbsolutePath().normalize()).append("\" runtime start \"")
                .append(instanceId.replace("\"", "")).append("\" 2>nul\r\nexit /b 0\r\n");
        Files.writeString(wrapper, text, StandardCharsets.UTF_8);
    }
    private static String value(List<String> lines, String key) { for(String line:lines)if(line.startsWith(key+":"))return line.substring(key.length()+1);return ""; }
    private static void replace(List<String> lines,String key,String value){for(int i=0;i<lines.size();i++)if(lines.get(i).startsWith(key+":")){lines.set(i,key+":"+value);return;}lines.add(key+":"+value);}
    private static String sha256(Path file)throws IOException{try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void ensureLauncherStopped(LauncherInstallation launcher) throws IOException {
        String name = launcher.executable().getFileName().toString().toLowerCase();
        boolean running = ProcessHandle.allProcesses().anyMatch(process -> process.info().command().map(command -> Path.of(command).getFileName().toString().toLowerCase().equals(name)).orElse(false));
        if (running) throw new IOException("Launcher is running; close it before modifying hook configuration");
    }
}
