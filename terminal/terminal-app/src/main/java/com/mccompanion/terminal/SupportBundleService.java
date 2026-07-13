package com.mccompanion.terminal;

import com.mccompanion.terminal.diagnostics.DiagnosticEngine;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a deliberately allow-listed and redacted support archive. */
final class SupportBundleService {
    private static final Pattern SECRET = Pattern.compile("(?i)(api[_-]?key|token|authorization|secret)(\\s*[:=]\\s*)([^,\\s}]+)");
    Path collect(MinecraftInstance instance, Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            StringBuilder summary = new StringBuilder("Minecraft=").append(instance.minecraftVersion()).append('\n')
                    .append("Loader=").append(instance.loader()).append(' ').append(instance.loaderVersion()).append('\n')
                    .append("Java=").append(instance.requiredJavaMajor()).append('\n')
                    .append("Instance=<INSTANCE>\n");
            new DiagnosticEngine().run(instance).forEach(result -> summary.append(result.severity()).append(' ')
                    .append(result.code()).append(' ').append(result.summary()).append('\n'));
            add(zip, "summary.txt", summary.toString());
            Path manifest = instance.gameDirectory().resolve(".mccompanion/install-manifest.json");
            if (Files.isRegularFile(manifest)) add(zip, "install-manifest.json", redact(Files.readString(manifest)));
            Path latest = instance.logsDirectory().resolve("latest.log");
            if (Files.isRegularFile(latest) && Files.size(latest) <= 16L * 1024 * 1024)
                add(zip, "minecraft-latest.log", redact(Files.readString(latest, StandardCharsets.UTF_8)));
            StringBuilder mods = new StringBuilder();
            if (Files.isDirectory(instance.modsDirectory())) try (var files = Files.newDirectoryStream(instance.modsDirectory(), "*.jar")) {
                for (Path file : files) mods.append(file.getFileName()).append('\n');
            }
            add(zip, "mods.txt", mods.toString());
        }
        return output;
    }
    private static String redact(String text) {
        String value = SECRET.matcher(text).replaceAll("$1$2<REDACTED>");
        value = value.replace(System.getProperty("user.home", ""), "<HOME>");
        String user = System.getProperty("user.name", "");
        if (!user.isBlank()) value = value.replace(user, "<USER>");
        return value.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~-]+", "Bearer <REDACTED>");
    }
    private static void add(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
