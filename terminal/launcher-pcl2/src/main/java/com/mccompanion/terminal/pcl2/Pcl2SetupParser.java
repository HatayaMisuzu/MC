package com.mccompanion.terminal.pcl2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Pcl2SetupParser {
    public Map<String, String> parse(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return values;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int separator = line.indexOf(':');
            if (separator > 0) values.put(line.substring(0, separator).strip(), line.substring(separator + 1).strip());
        }
        return values;
    }

    public Path resolveMinecraftRoot(Path launcherRoot, String value) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("PCL LaunchFolderSelect is empty");
        String normalized = value.replace('\\', '/');
        Path candidate;
        if (normalized.startsWith("$")) candidate = launcherRoot.resolve(normalized.substring(1));
        else {
            Path parsed = Path.of(normalized);
            candidate = parsed.isAbsolute() ? parsed : launcherRoot.resolve(parsed);
        }
        candidate = candidate.toAbsolutePath().normalize();
        if (normalized.startsWith("$") && !candidate.startsWith(launcherRoot.toAbsolutePath().normalize()))
            throw new IOException("PCL relative Minecraft path escapes launcher directory");
        return candidate;
    }
}
