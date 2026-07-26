package com.mccompanion.terminal;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebTerminalServerTest {
  @TempDir Path temporary;

  @Test
  void requiresBootstrapCookieAndCsrfAndRejectsCrossSiteOrigin() throws Exception {
    Path web = temporary.resolve("web");
    Files.createDirectories(web);
    Files.writeString(web.resolve("index.html"), "<!doctype html><title>MCAC</title>");
    ControlTerminalMain root = new ControlTerminalMain();
    root.suppliedRoots.add(temporary.resolve("empty-root"));
    Files.createDirectories(root.suppliedRoots.getFirst());
    HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER).build();
    try (WebTerminalServer server = new WebTerminalServer(root, web, 0, false, null)) {
      server.start();
      String origin = "http://127.0.0.1:" + server.port();

      var anonymous = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/status")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(401, anonymous.statusCode());

      var bootstrap = client.send(HttpRequest.newBuilder(server.bootstrapUri()).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(303, bootstrap.statusCode());
      String cookie = bootstrap.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
      URI location = URI.create(bootstrap.headers().firstValue("location").orElseThrow());
      String csrf = location.getFragment().substring("csrf=".length());

      var rejected = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/status"))
              .header("Cookie", cookie).header("X-MCAC-CSRF", csrf)
              .header("Origin", "https://attacker.invalid").GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(403, rejected.statusCode());

      var accepted = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/status"))
              .header("Cookie", cookie).header("X-MCAC-CSRF", csrf)
              .header("Origin", origin).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, accepted.statusCode());
      assertTrue(accepted.body().contains("\"loopbackOnly\":true"));

      URI window = URI.create(origin + "/api/window/open");
      String oversized = "x".repeat(TerminalRequestBodies.CONTROL_JSON_LIMIT + 1);
      var tooLarge = client.send(HttpRequest.newBuilder(window)
              .header("Cookie", cookie).header("X-MCAC-CSRF", csrf)
              .header("Origin", origin).header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(oversized)).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(413, tooLarge.statusCode(), tooLarge.body());
      assertTrue(tooLarge.body().contains("PAYLOAD_TOO_LARGE"));

      byte[] chunkedBytes = new byte[TerminalRequestBodies.CONTROL_JSON_LIMIT + 1];
      var chunked = client.send(HttpRequest.newBuilder(window)
              .header("Cookie", cookie).header("X-MCAC-CSRF", csrf)
              .header("Origin", origin).header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofInputStream(
                  () -> new java.io.ByteArrayInputStream(chunkedBytes))).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(413, chunked.statusCode(), chunked.body());
      assertTrue(chunked.body().contains("PAYLOAD_TOO_LARGE"));

      var compressed = client.send(HttpRequest.newBuilder(window)
              .header("Cookie", cookie).header("X-MCAC-CSRF", csrf)
              .header("Origin", origin).header("Content-Type", "application/json")
              .header("Content-Encoding", "gzip")
              .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {1, 2, 3})).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(415, compressed.statusCode(), compressed.body());
      assertTrue(compressed.body().contains("UNSUPPORTED_CONTENT_ENCODING"));

      var page = client.send(HttpRequest.newBuilder(URI.create(origin + "/")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, page.statusCode());
      assertTrue(page.headers().firstValue("content-security-policy").orElseThrow()
          .contains("frame-ancestors 'none'"));
    }
  }
}
