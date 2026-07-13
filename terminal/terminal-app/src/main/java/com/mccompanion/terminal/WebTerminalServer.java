package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Loopback-only HTML control terminal host. */
final class WebTerminalServer implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom RANDOM = new SecureRandom();
  private final ControlTerminalMain root;
  private final Path webRoot;
  private final boolean openBrowser;
  private final Path stateFile;
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final OperationManager operations = new OperationManager();
  private final WebTerminalApi api;
  private final CountDownLatch stopped = new CountDownLatch(1);
  private final String sessionToken = token();
  private final String csrfToken = token();
  private final String bootstrapTicket = token();
  private final Map<String, Instant> windows = new ConcurrentHashMap<>();
  private volatile String shutdownPlan;
  private volatile boolean closed;

  WebTerminalServer(
      ControlTerminalMain root, Path webRoot, int requestedPort, boolean openBrowser, Path stateFile)
      throws IOException {
    this.root = root;
    this.webRoot = webRoot.toAbsolutePath().normalize();
    this.openBrowser = openBrowser;
    this.stateFile = stateFile;
    if (!Files.isRegularFile(this.webRoot.resolve("index.html"))) {
      throw new IOException("HTML 终端资源不存在: " + this.webRoot);
    }
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 32);
    api = new WebTerminalApi(root, operations);
    server.createContext("/open/", this::bootstrap);
    server.createContext("/api/events", this::events);
    server.createContext("/api/logs/stream", this::logEvents);
    server.createContext("/api/window/", this::window);
    server.createContext("/api/server/", this::serverControl);
    server.createContext("/api/", this::api);
    server.createContext("/", this::staticFile);
    server.setExecutor(executor);
  }

  int port() {
    return server.getAddress().getPort();
  }

  URI bootstrapUri() {
    return URI.create("http://127.0.0.1:" + port() + "/open/" + bootstrapTicket);
  }

  void start() throws IOException {
    server.start();
    writeState();
    operations.publishSystem("SERVER_STARTED", "HTML 控制终端已在 Loopback 启动");
    if (openBrowser) openBrowser(bootstrapUri());
  }

  void await() throws InterruptedException {
    stopped.await();
  }

  private void bootstrap(HttpExchange exchange) throws IOException {
    try {
      securityHeaders(exchange);
      if (!"GET".equals(exchange.getRequestMethod())
          || !exchange.getRequestURI().getPath().equals("/open/" + bootstrapTicket)) {
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      exchange
          .getResponseHeaders()
          .add(
              "Set-Cookie",
              "MCAC_SESSION="
                  + sessionToken
                  + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400");
      exchange
          .getResponseHeaders()
          .set("Location", "http://127.0.0.1:" + port() + "/#csrf=" + csrfToken);
      exchange.sendResponseHeaders(303, -1);
    } finally {
      exchange.close();
    }
  }

  private void api(HttpExchange exchange) throws IOException {
    securityHeaders(exchange);
    if (!authenticated(exchange)) return;
    api.handle(exchange);
  }

  private void events(HttpExchange exchange) throws IOException {
    securityHeaders(exchange);
    if (!authenticated(exchange)) return;
    if (!"GET".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("Connection", "keep-alive");
    exchange.sendResponseHeaders(200, 0);
    var queue = operations.subscribe();
    String instanceId = queryParameter(exchange, "instanceId");
    try (exchange) {
      writeSse(exchange, JSON.createObjectNode().put("type", "STREAM_READY"));
      while (!closed) {
        ObjectNode event = queue.poll(2, TimeUnit.SECONDS);
        if (event == null) {
          ObjectNode status = JSON.createObjectNode().put("type", "STATUS");
          status.set("data", api.statusSnapshot());
          writeSse(exchange, status);
          if (instanceId != null && !instanceId.isBlank()) {
            try {
              ObjectNode companions = JSON.createObjectNode().put("type", "COMPANIONS");
              companions.set("data", api.companionSnapshot(instanceId));
              writeSse(exchange, companions);
            } catch (Exception failure) {
              writeSse(
                  exchange,
                  JSON.createObjectNode()
                      .put("type", "COMPANIONS_UNAVAILABLE")
                      .put(
                          "message",
                          failure.getMessage() == null
                              ? "snapshot unavailable"
                              : failure.getMessage()));
            }
          }
        } else writeSse(exchange, event);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (IOException disconnected) {
      // Normal when a browser tab closes or reloads.
    } finally {
      operations.unsubscribe(queue);
    }
  }

  private void logEvents(HttpExchange exchange) throws IOException {
    securityHeaders(exchange);
    if (!authenticated(exchange)) return;
    if (!"GET".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("Connection", "keep-alive");
    exchange.sendResponseHeaders(200, 0);
    try (exchange) {
      while (!closed) {
        ObjectNode event = JSON.createObjectNode().put("type", "LOG_SNAPSHOT");
        try {
          event.set("data", api.logSnapshot(exchange));
        } catch (Exception failure) {
          event
              .put("type", "LOG_UNAVAILABLE")
              .put(
                  "message",
                  failure.getMessage() == null ? "log unavailable" : failure.getMessage());
        }
        writeSse(exchange, event);
        Thread.sleep(1000);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (IOException disconnected) {
      // Normal when a browser tab closes or changes log source.
    }
  }

  private void window(HttpExchange exchange) throws IOException {
    securityHeaders(exchange);
    if (!authenticated(exchange)) return;
    if (!"POST".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    var request = JSON.readTree(exchange.getRequestBody());
    String id = request.path("windowId").asText("").trim();
    if (id.isEmpty() || id.length() > 128) {
      WebTerminalApi.sendError(exchange, 400, "INVALID_WINDOW", "窗口标识无效");
      return;
    }
    String path = exchange.getRequestURI().getPath();
    if (path.endsWith("/close")) windows.remove(id);
    else windows.put(id, Instant.now());
    WebTerminalApi.send(
        exchange,
        200,
        JSON.createObjectNode().put("ok", true).put("openWindows", windows.size()));
  }

  private void serverControl(HttpExchange exchange) throws IOException {
    securityHeaders(exchange);
    if (!authenticated(exchange)) return;
    String path = exchange.getRequestURI().getPath();
    if ("GET".equals(exchange.getRequestMethod()) && path.equals("/api/server/status")) {
      WebTerminalApi.send(
          exchange,
          200,
          JSON.createObjectNode()
              .put("port", port())
              .put("bind", "127.0.0.1")
              .put("loopbackOnly", true)
              .put("windows", windows.size()));
      return;
    }
    if (!"POST".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    if (path.equals("/api/server/stop/plan")) {
      shutdownPlan = UUID.randomUUID().toString();
      WebTerminalApi.send(
          exchange,
          200,
          JSON.createObjectNode()
              .put("planId", shutdownPlan)
              .put("dangerous", true)
              .put("summary", "停止本地 HTML 控制终端后台服务"));
      return;
    }
    if (path.equals("/api/server/stop/execute")) {
      var request = JSON.readTree(exchange.getRequestBody());
      String value = request.path("planId").asText();
      if (shutdownPlan == null
          || !constantTime(shutdownPlan, value)
          || !constantTime(value, request.path("confirmation").asText())) {
        WebTerminalApi.sendError(exchange, 400, "CONFIRMATION_REQUIRED", "停止计划确认失败");
        return;
      }
      shutdownPlan = null;
      WebTerminalApi.send(
          exchange, 202, JSON.createObjectNode().put("accepted", true).put("state", "STOPPING"));
      executor.submit(
          () -> {
            try {
              Thread.sleep(250);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
            close();
          });
      return;
    }
    WebTerminalApi.sendError(exchange, 404, "NOT_FOUND", "控制路径不存在");
  }

  private void staticFile(HttpExchange exchange) throws IOException {
    securityHeaders(exchange);
    if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    String raw = exchange.getRequestURI().getPath();
    String relative = raw.equals("/") ? "index.html" : raw.substring(1);
    if (relative.contains("..") || relative.contains("\\") || relative.indexOf('\0') >= 0) {
      exchange.sendResponseHeaders(400, -1);
      exchange.close();
      return;
    }
    Path file = webRoot.resolve(relative).normalize();
    if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) file = webRoot.resolve("index.html");
    byte[] bytes = Files.readAllBytes(file);
    exchange.getResponseHeaders().set("Content-Type", contentType(file));
    exchange
        .getResponseHeaders()
        .set(
            "Cache-Control",
            file.getFileName().toString().equals("index.html")
                ? "no-store"
                : "public, max-age=31536000, immutable");
    if ("HEAD".equals(exchange.getRequestMethod())) exchange.sendResponseHeaders(200, -1);
    else {
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  private boolean authenticated(HttpExchange exchange) throws IOException {
    if (!hostValid(exchange)) {
      exchange.sendResponseHeaders(421, -1);
      exchange.close();
      return false;
    }
    String fetchSite = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
    if ("cross-site".equalsIgnoreCase(fetchSite)) {
      exchange.sendResponseHeaders(403, -1);
      exchange.close();
      return false;
    }
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    String expectedOrigin = "http://127.0.0.1:" + port();
    if (origin != null && !constantTime(expectedOrigin, origin)) {
      exchange.sendResponseHeaders(403, -1);
      exchange.close();
      return false;
    }
    String cookie = exchange.getRequestHeaders().getFirst("Cookie");
    String csrf = exchange.getRequestHeaders().getFirst("X-MCAC-CSRF");
    if (!constantTime(sessionToken, cookie(cookie, "MCAC_SESSION"))
        || !constantTime(csrfToken, csrf)) {
      exchange.sendResponseHeaders(401, -1);
      exchange.close();
      return false;
    }
    return true;
  }

  private boolean hostValid(HttpExchange exchange) {
    String host = exchange.getRequestHeaders().getFirst("Host");
    return host != null && constantTime("127.0.0.1:" + port(), host);
  }

  private void securityHeaders(HttpExchange exchange) {
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    exchange
        .getResponseHeaders()
        .set(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                + "connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'");
  }

  private void writeState() throws IOException {
    if (stateFile == null) return;
    Path target = stateFile.toAbsolutePath().normalize();
    if (target.getParent() != null) Files.createDirectories(target.getParent());
    ObjectNode state =
        JSON.createObjectNode()
            .put("bind", "127.0.0.1")
            .put("port", port())
            .put("bootstrapUrl", bootstrapUri().toString())
            .put("startedAt", Instant.now().toString());
    JSON.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), state);
  }

  private static void writeSse(HttpExchange exchange, ObjectNode event) throws IOException {
    byte[] bytes =
        ("event: message\ndata: " + JSON.writeValueAsString(event) + "\n\n")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseBody().write(bytes);
    exchange.getResponseBody().flush();
  }

  static void openBrowser(URI uri) throws IOException {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
      Desktop.getDesktop().browse(uri);
      return;
    }
    new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString()).start();
  }

  private static String contentType(Path file) {
    String name = file.getFileName().toString().toLowerCase();
    if (name.endsWith(".html")) return "text/html; charset=utf-8";
    if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
    if (name.endsWith(".css")) return "text/css; charset=utf-8";
    if (name.endsWith(".svg")) return "image/svg+xml";
    if (name.endsWith(".png")) return "image/png";
    if (name.endsWith(".woff2")) return "font/woff2";
    return "application/octet-stream";
  }

  private static String cookie(String header, String name) {
    if (header == null) return null;
    for (String part : header.split(";")) {
      String value = part.trim();
      if (value.startsWith(name + "=")) return value.substring(name.length() + 1);
    }
    return null;
  }

  private static String queryParameter(HttpExchange exchange, String name) {
    String raw = exchange.getRequestURI().getRawQuery();
    if (raw == null) return null;
    for (String part : raw.split("&")) {
      String[] pair = part.split("=", 2);
      if (pair.length == 2 && pair[0].equals(name)) {
        return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static String token() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static boolean constantTime(String expected, String actual) {
    if (expected == null || actual == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    server.stop(1);
    operations.close();
    executor.shutdown();
    if (stateFile != null) {
      try {
        Files.deleteIfExists(stateFile.toAbsolutePath().normalize());
      } catch (IOException ignored) {
      }
    }
    stopped.countDown();
  }

  static Path locateWebRoot(Path explicit) {
    if (explicit != null) return explicit;
    Path cwd = Path.of("").toAbsolutePath();
    Path dev = cwd.resolve("terminal/web-ui/dist");
    if (Files.isRegularFile(dev.resolve("index.html"))) return dev;
    try {
      Path jar =
          Path.of(
              WebTerminalServer.class
                  .getProtectionDomain()
                  .getCodeSource()
                  .getLocation()
                  .toURI());
      Path image = (Files.isDirectory(jar) ? jar : jar.getParent()).getParent();
      Path packaged = image.resolve("web");
      if (Files.isRegularFile(packaged.resolve("index.html"))) return packaged;
    } catch (Exception ignored) {
    }
    return dev;
  }
}
