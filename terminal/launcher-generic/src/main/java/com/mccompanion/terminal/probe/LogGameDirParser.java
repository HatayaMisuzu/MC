package com.mccompanion.terminal.probe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves gameDir only from a recent launch block that also identifies the version and Minecraft root. */
public final class LogGameDirParser {
    private static final Pattern GAME_DIR = Pattern.compile("--gameDir(?:=|\\s+)(?:\"([^\"]+)\"|'([^']+)'|([^\\s]+))");
    private static final Duration MAX_LOG_AGE = Duration.ofDays(7);

    public Optional<Path> latest(List<Path> logs, String versionId, Path minecraftRoot) {
        Instant cutoff = Instant.now().minus(MAX_LOG_AGE);
        for (Path log : logs.stream().filter(Files::isRegularFile)
                .filter(path -> modified(path).isAfter(cutoff))
                .sorted(Comparator.comparing(LogGameDirParser::modified).reversed()).toList()) {
            try {
                List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
                for (int line = lines.size() - 1; line >= 0; line--) {
                    Matcher matcher = GAME_DIR.matcher(lines.get(line));
                    while (matcher.find()) {
                        String raw = matcher.group(1) != null ? matcher.group(1)
                                : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
                        Path gameDir = Path.of(raw).toAbsolutePath().normalize();
                        String context = String.join("\n", lines.subList(Math.max(0, line - 12),
                                Math.min(lines.size(), line + 13)));
                        if (mentionsVersion(context, versionId)
                                && (mentionsRoot(context, minecraftRoot) || gameDir.startsWith(minecraftRoot))) {
                            return Optional.of(gameDir);
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // A malformed or concurrently rotated log must not abort launcher discovery.
            }
        }
        return Optional.empty();
    }

    private static boolean mentionsVersion(String context, String versionId) {
        String quoted = Pattern.quote(versionId);
        return Pattern.compile("(?i)(?:--version(?:=|\\s+)|version(?:Id)?[=: ]+)[\"']?" + quoted
                + "(?:[\"'\\s]|$)").matcher(context).find();
    }

    private static boolean mentionsRoot(String context, Path root) {
        String value = root.toAbsolutePath().normalize().toString();
        return context.toLowerCase().contains(value.toLowerCase())
                || context.replace('\\', '/').toLowerCase().contains(value.replace('\\', '/').toLowerCase());
    }

    private static Instant modified(Path path) {
        try { return Files.getLastModifiedTime(path).toInstant(); }
        catch (IOException unavailable) { return Instant.EPOCH; }
    }
}
