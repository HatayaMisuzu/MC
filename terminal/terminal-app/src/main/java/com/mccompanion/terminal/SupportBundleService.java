package com.mccompanion.terminal;

import com.mccompanion.terminal.diagnostics.DiagnosticEngine;
import com.mccompanion.terminal.diagnostics.DiagnosticResult;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a deliberately allow-listed and redacted support archive. */
final class SupportBundleService {
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)([\\\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|authorization|secret|password|passwd|cookie|session[_-]?id|account|username|player[_-]?name|instance[_-]?id|installation[_-]?id|profile[_-]?id|companion[_-]?id|brain[_-]?session[_-]?id|controller[_-]?id)[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\\"',\\s}\\]]+)");
    private static final Pattern ABSOLUTE_PATH=Pattern.compile("(?i)[A-Z]:\\\\[^\\r\\n\"']+");
    private static final Pattern POSIX_PATH=Pattern.compile("(?<![:\\w])/(?:Users|home|var|tmp|opt|srv|mnt|etc)/[^\\s\"']+");
    private static final Pattern IPV4=Pattern.compile("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?");
    private static final Pattern IPV6=Pattern.compile("(?i)(?<![0-9a-f])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?:%[0-9a-z]+)?(?:\\:[0-9]{1,5})?");
    private static final Pattern UUID=Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
    private static final Pattern HOSTNAME=Pattern.compile("(?i)\\b(?!(?:brain|search|runtime|mcp|protocol|install|launcher|mods|hook|java|loader)\\.)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?!(?:jar|log|json|txt|ya?ml)\\b)[a-z]{2,63}(?::[0-9]{1,5})?\\b");
    private static final Pattern EMAIL=Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,63}\\b");
    private static final Pattern JWT=Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
    Path collect(MinecraftInstance instance, Path output) throws IOException {
        return collect(instance, null, List.of(), output);
    }
    Path collect(MinecraftInstance instance, RuntimeProfile profile, Path output) throws IOException {
        return collect(instance, profile, List.of(), output);
    }
    Path collect(MinecraftInstance instance, RuntimeProfile profile, List<DiagnosticResult> doctor,
                 Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            StringBuilder summary = new StringBuilder("Minecraft=").append(instance.minecraftVersion()).append('\n')
                    .append("Loader=").append(instance.loader()).append(' ').append(instance.loaderVersion()).append('\n')
                    .append("Java=").append(instance.requiredJavaMajor()).append('\n')
                    .append("Instance=<INSTANCE>\n");
            new DiagnosticEngine().run(instance).forEach(result -> summary.append(result.severity()).append(' ')
                    .append(result.code()).append(' ').append(result.summary()).append('\n'));
            add(zip, "summary.txt", redact(summary.toString()));
            Path manifest = instance.gameDirectory().resolve(".mccompanion/install-manifest.json");
            if (Files.isRegularFile(manifest)) add(zip, "install-manifest.json", redact(Files.readString(manifest)));
            Path latest = instance.logsDirectory().resolve("latest.log");
            if (Files.isRegularFile(latest) && Files.size(latest) <= 16L * 1024 * 1024)
                add(zip, "minecraft-errors.log", redact(safeErrorExcerpt(Files.readString(latest, StandardCharsets.UTF_8))));
            if (profile != null) {
                String runtimeSummary = "Profile=<PROFILE>\nConfigured=" + Files.isDirectory(profile.profileDirectory())
                        + "\nPort=" + profile.port() + "\nHealthPort=" + profile.healthPort() + "\n";
                add(zip, "runtime-summary.txt", redact(runtimeSummary));
                add(zip, "safe-config-summary.txt", redact(configurationSummary(profile)));
                Path runtimeLog = profile.logFile();
                if (Files.isRegularFile(runtimeLog) && Files.size(runtimeLog) <= 16L * 1024 * 1024) {
                    add(zip, "runtime-errors.log", redact(safeErrorExcerpt(Files.readString(runtimeLog, StandardCharsets.UTF_8))));
                }
            }
            if (!doctor.isEmpty()) {
                StringBuilder checks = new StringBuilder();
                doctor.forEach(result -> checks.append(result.severity()).append(' ').append(result.code())
                        .append(' ').append(result.summary()).append(' ').append(result.evidence()).append('\n'));
                add(zip, "doctor.txt", redact(checks.toString()));
            }
            add(zip, "reproduction-steps.txt", "1. Open the MCAC control terminal.\n"
                    + "2. Select the affected instance and run Doctor.\n"
                    + "3. Repeat the failed confirmed operation.\n"
                    + "4. Record the visible operation reason code and approximate time.\n");
            StringBuilder mods = new StringBuilder();
            if (Files.isDirectory(instance.modsDirectory())) try (var files = Files.newDirectoryStream(instance.modsDirectory(), "*.jar")) {
                for (Path file : files) mods.append(file.getFileName()).append('\n');
            }
            add(zip, "mods.txt", redact(mods.toString()));
        }
        verifySanitized(output);
        return output;
    }
    private static String configurationSummary(RuntimeProfile profile) throws IOException {
        var brain = new ProviderConfigurationService().status(profile);
        var search = new SearchConfigurationService().status(profile);
        String brainEnvironment = brain.path("apiKeyEnv").asText("");
        String searchEnvironment = search.path("tokenEnv").asText("");
        return "BrainMode=" + brain.path("mode").asText("rules") + '\n'
                + "BrainModel=" + brain.path("model").asText("rules") + '\n'
                + "BrainCredentialReference=" + (brainEnvironment.isBlank() ? "not-required" : brainEnvironment) + '\n'
                + "BrainCredentialPresent=" + (!brainEnvironment.isBlank() && present(brainEnvironment)) + '\n'
                + "SearchMode=" + search.path("mode").asText("disabled") + '\n'
                + "SearchCredentialReference=" + (searchEnvironment.isBlank() ? "not-required" : searchEnvironment) + '\n'
                + "SearchCredentialPresent=" + (!searchEnvironment.isBlank() && present(searchEnvironment)) + '\n';
    }
    private static boolean present(String environment) {
        String value = System.getenv(environment);
        return value != null && !value.isBlank();
    }
    private static String safeErrorExcerpt(String text) {
        String[] lines = text.split("\\R");
        StringBuilder result = new StringBuilder();
        int included = 0;
        for (int index = Math.max(0, lines.length - 2_000); index < lines.length && included < 200; index++) {
            String line = lines[index];
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            boolean error = lower.contains("error") || lower.contains("warn") || lower.contains("exception")
                    || lower.contains("caused by") || lower.matches("^\\s*at\\s+.*");
            boolean privateChat = lower.contains("[chat]") || lower.contains("chat message")
                    || lower.matches(".*<[^>]{1,64}>.*");
            if (error && !privateChat) {
                result.append(line, 0, Math.min(line.length(), 4_096)).append('\n');
                included++;
            }
        }
        return "ErrorLines=" + included + '\n' + result;
    }
    private static void verifySanitized(Path output)throws IOException{try(var zip=new java.util.zip.ZipFile(output.toFile())){var entries=zip.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();if(entry.isDirectory())continue;String text=new String(zip.getInputStream(entry).readAllBytes(),StandardCharsets.UTF_8);var secrets=SENSITIVE_ASSIGNMENT.matcher(text);while(secrets.find())if(!"<REDACTED>".equals(secrets.group(2)))throw new IOException("Support bundle secret scan failed: "+entry.getName());if(ABSOLUTE_PATH.matcher(text).find()||POSIX_PATH.matcher(text).find())throw new IOException("Support bundle path scan failed: "+entry.getName());if(IPV4.matcher(text).find()||IPV6.matcher(text).find())throw new IOException("Support bundle IP scan failed: "+entry.getName());if(UUID.matcher(text).find())throw new IOException("Support bundle UUID scan failed: "+entry.getName());if(EMAIL.matcher(text).find())throw new IOException("Support bundle email scan failed: "+entry.getName());if(JWT.matcher(text).find())throw new IOException("Support bundle JWT scan failed: "+entry.getName());if(HOSTNAME.matcher(text).find())throw new IOException("Support bundle hostname scan failed: "+entry.getName());}}}
    private static String redact(String text) {
        String value = SENSITIVE_ASSIGNMENT.matcher(text).replaceAll("$1<REDACTED>");
        String home=System.getProperty("user.home", "");
        if(!home.isBlank())value = value.replace(home, "<HOME>");
        String user = System.getProperty("user.name", "");
        if (!user.isBlank()) value = value.replace(user, "<USER>");
        value=value.replaceAll("(?i)Bearer\\s+[^\\s,]+", "Bearer <REDACTED>")
                .replaceAll("(?i)([?&](?:key|token|secret|signature|auth)=)[^&\\s]+","$1<REDACTED>")
                .replaceAll("(?i)(Authorization\\s*:\\s*)[^\\r\\n]+","$1<REDACTED>");
        value=EMAIL.matcher(value).replaceAll("<EMAIL>");
        value=JWT.matcher(value).replaceAll("<REDACTED>");
        value=ABSOLUTE_PATH.matcher(value).replaceAll("<PATH>");
        value=POSIX_PATH.matcher(value).replaceAll("<PATH>");
        value=IPV4.matcher(value).replaceAll("<IP>");
        value=IPV6.matcher(value).replaceAll("<IP>");
        value=UUID.matcher(value).replaceAll("<UUID>");
        return HOSTNAME.matcher(value).replaceAll("<HOST>");
    }
    private static void add(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
