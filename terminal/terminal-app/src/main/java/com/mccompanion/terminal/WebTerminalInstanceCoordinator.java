package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Owns the per-user HTML terminal lock and reopens the already-running control window. */
final class WebTerminalInstanceCoordinator {
  private static final ObjectMapper JSON = new ObjectMapper();

  private WebTerminalInstanceCoordinator() {}

  static void run(ControlTerminalMain root, WebTerminalOptions options) throws Exception {
    Path home = ControlTerminalMain.controlHome();
    Files.createDirectories(home);
    Path lockPath = home.resolve("html-terminal.lock");
    Path currentPath = home.resolve("html-terminal-current.json");
    try (FileChannel channel =
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      FileLock lock = tryLock(channel);
      if (lock == null) {
        var current = awaitCurrent(currentPath);
        String url = current.path("bootstrapUrl").asText();
        if (!url.startsWith("http://127.0.0.1:"))
          throw new IOException("Existing HTML terminal state is invalid");
        if (options.openBrowser()) WebTerminalServer.openBrowser(java.net.URI.create(url));
        System.out.println("MCAC HTML terminal already running: " + url);
        return;
      }
      try (lock;
          WebTerminalServer server =
              new WebTerminalServer(
                  root,
                  WebTerminalServer.locateWebRoot(options.webRoot()),
                  options.port(),
                  options.openBrowser(),
                  options.stateFile())) {
        server.start();
        writeCurrent(currentPath, server);
        System.out.println("MCAC HTML terminal: http://127.0.0.1:" + server.port());
        server.await();
      } finally {
        Files.deleteIfExists(currentPath);
      }
    }
  }

  private static FileLock tryLock(FileChannel channel) throws IOException {
    try {
      return channel.tryLock();
    } catch (OverlappingFileLockException heldInProcess) {
      return null;
    }
  }

  private static com.fasterxml.jackson.databind.JsonNode awaitCurrent(Path current) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(current)) {
        try {
          return JSON.readTree(current.toFile());
        } catch (IOException incompleteWrite) {
          // The owning process may be between atomic replace steps.
        }
      }
      Thread.sleep(50);
    }
    throw new IOException("Existing HTML terminal did not publish a reopen URL");
  }

  private static void writeCurrent(Path current, WebTerminalServer server) throws IOException {
    Path temporary = Files.createTempFile(current.getParent(), ".html-terminal-", ".tmp");
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(
            temporary.toFile(),
            JSON.createObjectNode()
                .put("bind", "127.0.0.1")
                .put("port", server.port())
                .put("bootstrapUrl", server.bootstrapUri().toString())
                .put("startedAt", Instant.now().toString()));
    try {
      Files.move(
          temporary,
          current,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, current, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
