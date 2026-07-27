package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Shared parser boundary for every Terminal HTTP JSON request. */
final class TerminalRequestBodies {
  static final int JSON_LIMIT = 1024 * 1024;
  static final int CONTROL_JSON_LIMIT = 16 * 1024;
  static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
  private static final Semaphore READERS = new Semaphore(8, true);

  private TerminalRequestBodies() {}

  static JsonNode readJson(HttpExchange exchange, ObjectMapper mapper, int maximumBytes)
      throws IOException, Failure {
    byte[] bytes = read(exchange, maximumBytes);
    if (bytes.length == 0) return mapper.createObjectNode();
    try {
      return mapper.readTree(bytes);
    } catch (IOException | RuntimeException invalid) {
      throw new Failure(400, "INVALID_JSON", "request body is not valid JSON");
    }
  }

  private static byte[] read(HttpExchange exchange, int maximumBytes)
      throws IOException, Failure {
    String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
    if (encoding != null && !encoding.isBlank() && !encoding.equalsIgnoreCase("identity")) {
      throw new Failure(415, "UNSUPPORTED_CONTENT_ENCODING",
          "compressed request bodies are not accepted");
    }
    var lengths = exchange.getRequestHeaders().get("Content-Length");
    var transfers = exchange.getRequestHeaders().get("Transfer-Encoding");
    if (lengths != null && !lengths.isEmpty() && transfers != null && !transfers.isEmpty()) {
      throw new Failure(400, "AMBIGUOUS_BODY_LENGTH",
          "Content-Length and Transfer-Encoding cannot be combined");
    }
    Long declared = null;
    if (lengths != null && !lengths.isEmpty()) {
      if (lengths.size() != 1 || lengths.get(0).contains(",")) {
        throw new Failure(400, "INVALID_CONTENT_LENGTH",
            "exactly one Content-Length is required");
      }
      try {
        declared = Long.parseLong(lengths.get(0));
      } catch (NumberFormatException invalid) {
        throw new Failure(400, "INVALID_CONTENT_LENGTH",
            "Content-Length is not a non-negative integer");
      }
      if (declared < 0) {
        throw new Failure(400, "INVALID_CONTENT_LENGTH",
            "Content-Length is not a non-negative integer");
      }
      if (declared > maximumBytes) {
        throw new Failure(413, "PAYLOAD_TOO_LARGE",
            "request body exceeds the endpoint byte limit");
      }
    }
    if (!READERS.tryAcquire()) {
      throw new Failure(503, "BODY_READER_BUSY",
          "bounded request-body reader capacity is saturated");
    }
    FutureTask<byte[]> pending =
        new FutureTask<>(() -> readAtMost(exchange.getRequestBody(), maximumBytes));
    Thread.ofVirtual().name("mcac-terminal-body-reader").start(pending);
    try {
      byte[] bytes = pending.get(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (declared != null && declared != bytes.length) {
        throw new Failure(400, "CONTENT_LENGTH_MISMATCH",
            "Content-Length does not match the received body");
      }
      return bytes;
    } catch (java.util.concurrent.TimeoutException timeout) {
      pending.cancel(true);
      throw new Failure(408, "REQUEST_BODY_TIMEOUT",
          "request body was not received within the bounded deadline");
    } catch (java.util.concurrent.ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof Failure failure) throw failure;
      if (cause instanceof IOException ioFailure) throw ioFailure;
      throw new IOException("request body read failed", cause);
    } catch (InterruptedException interrupted) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new IOException("request body read interrupted", interrupted);
    } finally {
      READERS.release();
    }
  }

  private static byte[] readAtMost(InputStream input, int maximumBytes)
      throws IOException, Failure {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
    byte[] buffer = new byte[8192];
    int total = 0;
    while (true) {
      int read = input.read(buffer, 0, Math.min(buffer.length, maximumBytes + 1 - total));
      if (read < 0) break;
      if (read == 0) continue;
      output.write(buffer, 0, read);
      total += read;
      if (total > maximumBytes) {
        throw new Failure(413, "PAYLOAD_TOO_LARGE",
            "request body exceeds the endpoint byte limit");
      }
    }
    return output.toByteArray();
  }

  static final class Failure extends Exception {
    private final int status;
    private final String code;

    private Failure(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }

    int status() {
      return status;
    }

    String code() {
      return code;
    }
  }
}
