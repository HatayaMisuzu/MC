package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.json.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Environment-only preflight. The sole optional argument is a report path, never a secret. */
public final class LiveBrainHarnessMain {
    private LiveBrainHarnessMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("usage: LiveBrainHarnessMain [report.json]");
        var report = LiveBrainHarnessContract.preflight(System.getenv(), Instant.now());
        String json = Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + System.lineSeparator();
        if (args.length == 0) System.out.print(json);
        else {
            Path target = Path.of(args[0]).toAbsolutePath().normalize();
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(target, json, StandardCharsets.UTF_8);
        }
    }
}
