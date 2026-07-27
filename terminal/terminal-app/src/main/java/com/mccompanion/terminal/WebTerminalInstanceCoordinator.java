package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        int port = current.path("port").asInt(-1);
        if (!"127.0.0.1".equals(current.path("bind").asText()) || port < 1 || port > 65535)
          throw new IOException("Existing HTML terminal state is invalid");
        if (options.openBrowser()) WebTerminalServer.openBrowser(requestBootstrap(current));
        System.out.println("MCAC HTML terminal already running: http://127.0.0.1:" + port);
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
                .put("ownerPid", ProcessHandle.current().pid())
                .put("serverInstanceId", server.serverInstanceId())
                .put("reopenSecret", server.reopenSecret())
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

  private static URI requestBootstrap(com.fasterxml.jackson.databind.JsonNode current)
      throws IOException, InterruptedException {
    int port = current.path("port").asInt(-1);
    String secret = current.path("reopenSecret").asText();
    if (port < 1 || port > 65535 || secret.isBlank()) {
      throw new IOException("Existing HTML terminal reopen state is invalid");
    }
    URI endpoint = URI.create("http://127.0.0.1:" + port + "/internal/reopen");
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(endpoint)
                    .header("X-MCAC-Reopen-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("Existing HTML terminal refused reopen request");
    }
    URI bootstrap = URI.create(JSON.readTree(response.body()).path("bootstrapUrl").asText());
    if (!bootstrap.toString().startsWith("http://127.0.0.1:" + port + "/open/")) {
      throw new IOException("Existing HTML terminal returned an invalid bootstrap URL");
    }
    return bootstrap;
  }
}
