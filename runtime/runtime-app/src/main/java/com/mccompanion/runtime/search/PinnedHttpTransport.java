package com.mccompanion.runtime.search;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal HTTP/1.1 transport whose socket connects only to pre-validated DNS answers. */
final class PinnedHttpTransport implements SearchHttpTransport {
    @Override public SearchHttpTransport.Response send(SearchSecurity.ResolvedTarget target, String method,
                                                       Map<String, String> requestHeaders, byte[] body,
                                                       Duration timeout, int maximumBytes) throws IOException {
        IOException failure = null;
        for (InetAddress address : target.addresses()) {
            try {
                return sendTo(target, address, method, requestHeaders, body, timeout, maximumBytes);
            } catch (IOException candidate) {
                failure = candidate;
            }
        }
        throw failure == null ? new IOException("SEARCH_HOST_UNRESOLVED") : failure;
    }

    private SearchHttpTransport.Response sendTo(SearchSecurity.ResolvedTarget target, InetAddress address,
                                                String method, Map<String, String> requestHeaders, byte[] body,
                                                Duration timeout, int maximumBytes) throws IOException {
        int timeoutMillis = Math.toIntExact(Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis())));
        int port = target.uri().getPort() >= 0 ? target.uri().getPort()
                : ("https".equalsIgnoreCase(target.uri().getScheme()) ? 443 : 80);
        Socket connected = new Socket();
        connected.connect(new InetSocketAddress(address, port), timeoutMillis);
        connected.setSoTimeout(timeoutMillis);
        Socket socket = connected;
        if ("https".equalsIgnoreCase(target.uri().getScheme())) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket(connected, target.host(), port, true);
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(List.of(new SNIHostName(target.host())));
            ssl.setSSLParameters(parameters);
            ssl.startHandshake();
            socket = ssl;
        }
        Socket responseSocket = socket;
        try (responseSocket) {
            OutputStream output = responseSocket.getOutputStream();
            String path = target.uri().getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (target.uri().getRawQuery() != null) path += "?" + target.uri().getRawQuery();
            String defaultPort = port == ("https".equalsIgnoreCase(target.uri().getScheme()) ? 443 : 80)
                    ? "" : ":" + port;
            StringBuilder head = new StringBuilder()
                    .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(target.host()).append(defaultPort).append("\r\n")
                    .append("Connection: close\r\n")
                    .append("Accept-Encoding: identity\r\n");
            for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                requireHeader(header.getKey(), header.getValue());
                head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
            if (body.length > 0) head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("\r\n");
            output.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
            return readResponse(responseSocket.getInputStream(), maximumBytes);
        } catch (SocketTimeoutException failure) {
            throw new IOException("SEARCH_TIMEOUT", failure);
        }
    }

    private static SearchHttpTransport.Response readResponse(InputStream input, int maximumBytes) throws IOException {
        byte[] headerBytes = readHeader(input, 65_536);
        String[] lines = new String(headerBytes, StandardCharsets.ISO_8859_1).split("\\r\\n");
        if (lines.length == 0 || !lines[0].matches("HTTP/1\\.[01] [0-9]{3}.*")) {
            throw new IOException("SEARCH_INVALID_HTTP_RESPONSE");
        }
        int status = Integer.parseInt(lines[0].substring(9, 12));
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int separator = lines[i].indexOf(':');
            if (separator <= 0) throw new IOException("SEARCH_INVALID_HTTP_HEADER");
            String name = lines[i].substring(0, separator).strip().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(separator + 1).strip();
            headers.merge(name, value, (left, right) -> left + "," + right);
        }
        String encoding = headers.getOrDefault("content-encoding", "identity");
        if (!encoding.equalsIgnoreCase("identity")) throw new IOException("SEARCH_CONTENT_ENCODING_DENIED");
        byte[] responseBody;
        String transfer = headers.get("transfer-encoding");
        if (transfer != null) {
            if (!transfer.equalsIgnoreCase("chunked") || headers.containsKey("content-length")) {
                throw new IOException("SEARCH_AMBIGUOUS_RESPONSE_FRAMING");
            }
            responseBody = readChunked(input, maximumBytes);
        } else if (headers.containsKey("content-length")) {
            long length;
            try { length = Long.parseLong(headers.get("content-length")); }
            catch (NumberFormatException failure) { throw new IOException("SEARCH_INVALID_CONTENT_LENGTH", failure); }
            if (length < 0 || length > maximumBytes) throw new IOException("SEARCH_RESPONSE_TOO_LARGE");
            responseBody = readExact(input, Math.toIntExact(length));
        } else {
            responseBody = readUntilEof(input, maximumBytes);
        }
        return new SearchHttpTransport.Response(status, Map.copyOf(headers), responseBody);
    }

    private static byte[] readHeader(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        while (output.size() < maximum) {
            int value = input.read();
            if (value < 0) throw new EOFException("SEARCH_TRUNCATED_HTTP_HEADER");
            output.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : (value == '\r' ? 1 : 0);
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> matched;
            };
            if (matched == 4) return output.toByteArray();
        }
        throw new IOException("SEARCH_HTTP_HEADERS_TOO_LARGE");
    }

    private static byte[] readChunked(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(input, 128);
            int extension = line.indexOf(';');
            String sizeText = (extension < 0 ? line : line.substring(0, extension)).strip();
            long size;
            try { size = Long.parseLong(sizeText, 16); }
            catch (NumberFormatException failure) { throw new IOException("SEARCH_INVALID_CHUNK", failure); }
            if (size == 0) {
                while (!readLine(input, 8_192).isEmpty()) { }
                return output.toByteArray();
            }
            if (size < 0 || size > maximum - output.size()) throw new IOException("SEARCH_RESPONSE_TOO_LARGE");
            output.write(readExact(input, Math.toIntExact(size)));
            if (!readLine(input, 2).isEmpty()) throw new IOException("SEARCH_INVALID_CHUNK");
        }
    }

    private static String readLine(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (output.size() <= maximum) {
            int value = input.read();
            if (value < 0) throw new EOFException("SEARCH_TRUNCATED_HTTP_RESPONSE");
            if (value == '\r') {
                if (input.read() != '\n') throw new IOException("SEARCH_INVALID_HTTP_LINE");
                return output.toString(StandardCharsets.US_ASCII);
            }
            output.write(value);
        }
        throw new IOException("SEARCH_HTTP_LINE_TOO_LARGE");
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("SEARCH_TRUNCATED_HTTP_BODY");
        return bytes;
    }

    private static byte[] readUntilEof(InputStream input, int maximum) throws IOException {
        byte[] bytes = input.readNBytes(maximum + 1);
        if (bytes.length > maximum) throw new IOException("SEARCH_RESPONSE_TOO_LARGE");
        return bytes;
    }

    private static void requireHeader(String name, String value) {
        if (!name.matches("[A-Za-z0-9-]{1,64}") || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("SEARCH_INVALID_REQUEST_HEADER");
        }
    }

    @Override public void close() { }
}
