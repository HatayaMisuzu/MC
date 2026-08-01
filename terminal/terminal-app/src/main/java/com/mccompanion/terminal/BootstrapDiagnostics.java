package com.mccompanion.terminal;

import com.mccompanion.protocol.security.OwnerOnlyFile;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Small, dependency-free startup journal for failures after the packaged JVM is entered. */
final class BootstrapDiagnostics {
  private static final long MAX_BYTES = 512 * 1024;
  private static Path logFile;

  private BootstrapDiagnostics() {}

  static synchronized void install(String[] arguments) {
    installAt(ControlTerminalMain.controlHome().resolve("bootstrap.log"), arguments);
  }

  static synchronized void installAt(Path destination, String[] arguments) {
    logFile = destination;
    rotateIfNeeded();
    note("bootstrap", "pid=" + ProcessHandle.current().pid() + " mode=" + mode(arguments));
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, failure) -> failure("uncaught:" + safe(thread.getName()), failure));
  }

  static synchronized void note(String stage, String detail) {
    append(Instant.now() + " INFO " + safe(stage) + " " + safe(detail));
  }

  static synchronized void failure(String stage, Throwable failure) {
    if (failure == null) {
      append(Instant.now() + " ERROR " + safe(stage) + " unknown");
      return;
    }
    StringWriter buffer = new StringWriter();
    failure.printStackTrace(new PrintWriter(buffer));
    String[] lines = buffer.toString().split("\\R");
    append(
        Instant.now()
            + " ERROR "
            + safe(stage)
            + " type="
            + failure.getClass().getName()
            + " message="
            + safe(failure.getMessage()));
    for (int index = 0; index < Math.min(lines.length, 32); index++) {
      append("  " + safe(lines[index]));
    }
  }

  private static void append(String line) {
    if (logFile == null) return;
    try {
      Files.createDirectories(logFile.getParent());
      Files.writeString(
          logFile,
          line + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      OwnerOnlyFile.secure(logFile);
    } catch (Exception ignored) {
      // Startup diagnostics must never replace the original startup outcome.
    }
  }

  private static void rotateIfNeeded() {
    if (logFile == null) return;
    try {
      if (!Files.isRegularFile(logFile) || Files.size(logFile) < MAX_BYTES) return;
      Path previous = logFile.resolveSibling("bootstrap.previous.log");
      Files.move(logFile, previous, StandardCopyOption.REPLACE_EXISTING);
      OwnerOnlyFile.secure(previous);
    } catch (Exception ignored) {
      // A locked or unreadable old log must not stop the product from starting.
    }
  }

  private static String mode(String[] arguments) {
    if (arguments.length == 0) return "web-default";
    return switch (arguments[0]) {
      case "web", "--tui", "--version", "--help" -> arguments[0];
      default -> "cli";
    };
  }

  private static String safe(String value) {
    if (value == null) return "";
    return value.replaceAll("[\\r\\n\\t]+", " ").strip();
  }
}
