package com.mccompanion.runtime;

import com.mccompanion.runtime.config.RuntimeConfig;

import java.nio.file.Path;
import java.util.Arrays;

public final class RuntimeMain {
    private RuntimeMain() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        if (arguments.help) {
            System.out.println("Usage: runtime-app [--config <runtime.yml>] [--no-cli]");
            return;
        }
        RuntimeConfig config = RuntimeConfig.load(arguments.config);
        RuntimeApplication application = RuntimeApplication.start(config, !arguments.noCli);
        Thread shutdownHook = new Thread(application::close, "mc-companion-runtime-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            application.awaitShutdown();
        } finally {
            application.close();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // The JVM is already executing shutdown hooks.
            }
        }
    }

    private record Arguments(Path config, boolean noCli, boolean help) {
        private static Arguments parse(String[] args) {
            Path config = Path.of("config", "runtime.yml");
            boolean noCli = false;
            boolean help = false;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--config" -> {
                        if (++index >= args.length || args[index].isBlank()) {
                            throw new IllegalArgumentException("--config requires a path");
                        }
                        config = Path.of(args[index]);
                    }
                    case "--no-cli" -> noCli = true;
                    case "--help", "-h" -> help = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[index]
                            + " (received " + Arrays.toString(args) + ')');
                }
            }
            return new Arguments(config, noCli, help);
        }
    }
}

