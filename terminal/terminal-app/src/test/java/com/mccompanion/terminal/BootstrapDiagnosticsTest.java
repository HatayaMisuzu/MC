package com.mccompanion.terminal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapDiagnosticsTest {
  @TempDir Path temp;

  @Test
  void startupModeNeverCopiesCommandLineSecrets() {
    Path log = temp.resolve("bootstrap.log");
    BootstrapDiagnostics.installAt(log, new String[] {"provider", "--api-key", "do-not-log"});
    BootstrapDiagnostics.failure("test", new IllegalStateException("stable failure"));
    assertTrue(Files.isRegularFile(log));
    String content = assertDoesNotThrowRead(log);
    assertTrue(content.contains("mode=cli"));
    assertTrue(content.contains("stable failure"));
    assertFalse(content.contains("do-not-log"));
  }

  private static String assertDoesNotThrowRead(Path path) {
    try {
      return Files.readString(path);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }
}
