package com.mccompanion.runtime.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class RuntimeLog implements AutoCloseable {
    private final Logger logger;
    private final Redactor redactor;

    public RuntimeLog(Path file, boolean console, Redactor redactor) throws IOException {
        this.redactor = redactor;
        this.logger = Logger.getLogger("mc-companion-runtime-" + System.identityHashCode(this));
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // A single bounded generation keeps the configured filename exact (runtime.log instead of runtime.log.0)
        // while preventing unbounded growth. Durable task/action history lives in SQLite, not in this text log.
        FileHandler fileHandler = new FileHandler(file.toString(), 2 * 1024 * 1024, 1, true);
        fileHandler.setEncoding("UTF-8");
        fileHandler.setFormatter(new SafeFormatter());
        logger.addHandler(fileHandler);
        if (console) {
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(new SafeFormatter());
            logger.addHandler(consoleHandler);
        }
    }

    public void info(String message) {
        logger.info(redactor.redact(message));
    }

    public void warn(String message) {
        logger.warning(redactor.redact(message));
    }

    public void error(String message, Throwable failure) {
        LogRecord record = new LogRecord(Level.SEVERE, redactor.redact(message));
        if (failure != null) {
            record.setThrown(new SanitizedFailure(failure.getClass().getSimpleName(), redactor.redact(failure.getMessage())));
        }
        logger.log(record);
    }

    @Override
    public void close() {
        for (Handler handler : logger.getHandlers()) {
            handler.flush();
            handler.close();
            logger.removeHandler(handler);
        }
    }

    private static final class SafeFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            StringBuilder output = new StringBuilder()
                    .append(Instant.ofEpochMilli(record.getMillis()))
                    .append(' ').append(record.getLevel().getName())
                    .append(' ').append(formatMessage(record)).append(System.lineSeparator());
            if (record.getThrown() != null) {
                output.append(record.getThrown().getClass().getSimpleName()).append(": ")
                        .append(record.getThrown().getMessage()).append(System.lineSeparator());
            }
            return output.toString();
        }
    }

    private static final class SanitizedFailure extends RuntimeException {
        private SanitizedFailure(String type, String message) {
            super(type + (message == null || message.isBlank() ? "" : ": " + message), null, false, false);
        }
    }
}
