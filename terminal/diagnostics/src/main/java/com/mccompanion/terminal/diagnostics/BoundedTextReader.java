package com.mccompanion.terminal.diagnostics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Bounded, replacement-aware reader for Windows, Minecraft and third-party text logs. */
public final class BoundedTextReader {
    private static final Charset GB18030 = Charset.forName("GB18030");

    private BoundedTextReader() { }

    public static Result readTail(Path file, int maxBytes) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        if (!Files.isRegularFile(file)) return new Result("", "NONE", 0, false, 0, 0, Stability.MISSING);

        // A short retry makes a concurrently appended/rotated log converge to one stable window;
        // the second observation remains explicitly marked if the producer never settled.
        Result last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            FileStamp before = stamp(file);
            if (before == null) return new Result("", "NONE", 0, false, 0, 0, Stability.MISSING);
            Encoding encoding = detectEncoding(file, before.size());
            long start = tailStart(before.size(), maxBytes, encoding);
            int length = (int) Math.min(maxBytes, Math.max(0L, before.size() - start));
            byte[] bytes = readBytes(file, start, length);
            FileStamp after = stamp(file);
            Stability stability = classify(before, after);
            last = decodeWindow(bytes, start, before.size(), encoding, stability, maxBytes);
            if (stability == Stability.STABLE || attempt == 1) return last;
        }
        return last;
    }

    /** Returns an explicit BOM charset, or null when the producer uses an auto-detected encoding. */
    public static Charset detectBomCharset(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        FileStamp stamp = stamp(file);
        return stamp == null ? null : detectEncoding(file, stamp.size()).bomCharset();
    }

    public static int detectBomBytes(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return 0;
        FileStamp stamp = stamp(file);
        return stamp == null ? 0 : detectEncoding(file, stamp.size()).bomBytes();
    }

    private static Result decodeWindow(byte[] bytes, long start, long sourceBytes,
                                       Encoding encoding, Stability stability, int maxBytes) {
        int from = start > 0 ? firstCompleteLine(bytes, encoding) : 0;
        byte[] bounded = java.util.Arrays.copyOfRange(bytes, Math.min(from, bytes.length), bytes.length);
        Decoded decoded = encoding.bomCharset() == null
                ? decode(bounded, false)
                : decode(bounded, encoding.bomCharset(), start == 0);
        return new Result(decoded.text(), decoded.charset(), decoded.replacementCount(),
                start > 0, bounded.length, sourceBytes, stability);
    }

    private static long tailStart(long size, int maxBytes, Encoding encoding) {
        if (size <= maxBytes) return 0;
        long start = size - maxBytes;
        if (encoding.bomCharset() != null && isUtf16(encoding.bomCharset())) {
            long dataStart = encoding.bomBytes();
            if (start < dataStart) start = dataStart;
            if (((start - dataStart) & 1L) != 0) start--;
        }
        return Math.max(0, start);
    }

    private static byte[] readBytes(Path file, long start, int length) throws IOException {
        byte[] bytes = new byte[length];
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) break;
                if (read == 0) Thread.yield();
            }
            if (buffer.position() != bytes.length) {
                return java.util.Arrays.copyOf(bytes, buffer.position());
            }
        }
        return bytes;
    }

    private static int firstCompleteLine(byte[] bytes, Encoding encoding) {
        if (encoding.bomCharset() != null && isUtf16(encoding.bomCharset())) {
            boolean little = encoding.bomCharset().equals(StandardCharsets.UTF_16LE);
            for (int index = 0; index + 1 < bytes.length; index += 2) {
                if ((little && bytes[index] == 0x0a && bytes[index + 1] == 0x00)
                        || (!little && bytes[index] == 0x00 && bytes[index + 1] == 0x0a)) {
                    return index + 2;
                }
            }
            return bytes.length;
        }
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\n') return index + 1;
        }
        return bytes.length;
    }

    private static Encoding detectEncoding(Path file, long size) throws IOException {
        int length = (int) Math.min(3, Math.max(0, size));
        if (length == 0) return new Encoding(null, 0);
        byte[] prefix = readBytes(file, 0, length);
        if (prefix.length >= 3 && prefix[0] == (byte) 0xef
                && prefix[1] == (byte) 0xbb && prefix[2] == (byte) 0xbf) {
            return new Encoding(StandardCharsets.UTF_8, 3);
        }
        if (prefix.length >= 2 && prefix[0] == (byte) 0xff && prefix[1] == (byte) 0xfe) {
            return new Encoding(StandardCharsets.UTF_16LE, 2);
        }
        if (prefix.length >= 2 && prefix[0] == (byte) 0xfe && prefix[1] == (byte) 0xff) {
            return new Encoding(StandardCharsets.UTF_16BE, 2);
        }
        return new Encoding(null, 0);
    }

    private static FileStamp stamp(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        return new FileStamp(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime().toMillis());
    }

    private static Stability classify(FileStamp before, FileStamp after) {
        if (after == null) return Stability.REPLACED_DURING_READ;
        if (before.fileKey() != null && after.fileKey() != null
                && !before.fileKey().equals(after.fileKey())) return Stability.REPLACED_DURING_READ;
        if (after.size() > before.size()) return Stability.APPENDED_DURING_READ;
        if (after.size() < before.size()) return Stability.TRUNCATED_DURING_READ;
        if (after.modified() != before.modified()) return Stability.UNSTABLE;
        return Stability.STABLE;
    }

    public static Decoded decode(byte[] bytes, boolean allowBom) {
        int offset = 0;
        Charset bomCharset = null;
        if (allowBom && bytes.length >= 3 && bytes[0] == (byte) 0xef
                && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            offset = 3;
            bomCharset = StandardCharsets.UTF_8;
        } else if (allowBom && bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            offset = 2;
            bomCharset = StandardCharsets.UTF_16LE;
        } else if (allowBom && bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            offset = 2;
            bomCharset = StandardCharsets.UTF_16BE;
        }
        if (bomCharset != null) return decode(bytes, bomCharset, true);
        return decodeAuto(ByteBuffer.wrap(bytes));
    }

    /** Decodes a tail with a BOM charset discovered from the file prefix. */
    public static Decoded decode(byte[] bytes, Charset charset, boolean allowBom) {
        int offset = allowBom && bytes.length >= 2 && isBom(bytes, charset) ? 2
                : allowBom && charset.equals(StandardCharsets.UTF_8) && bytes.length >= 3
                    && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf ? 3 : 0;
        return decodeReplacing(ByteBuffer.wrap(bytes, offset, bytes.length - offset), charset, charset.name());
    }

    private static Decoded decodeAuto(ByteBuffer data) {
        try {
            return new Decoded(decodeStrict(data, StandardCharsets.UTF_8), "UTF-8", 0);
        } catch (CharacterCodingException invalidUtf8) {
            data.rewind();
            try {
                return new Decoded(decodeStrict(data, GB18030), "GB18030", 0);
            } catch (CharacterCodingException invalidFallback) {
                data.rewind();
                return decodeReplacing(data, StandardCharsets.UTF_8, "UTF-8-REPLACEMENT");
            }
        }
    }

    private static boolean isBom(byte[] bytes, Charset charset) {
        return charset.equals(StandardCharsets.UTF_16LE)
                ? bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe
                : charset.equals(StandardCharsets.UTF_16BE)
                    ? bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff
                    : false;
    }

    private static boolean isUtf16(Charset charset) {
        return charset.equals(StandardCharsets.UTF_16LE) || charset.equals(StandardCharsets.UTF_16BE);
    }

    private static String decodeStrict(ByteBuffer bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
    }

    private static Decoded decodeReplacing(ByteBuffer bytes, Charset charset, String label) {
        try {
            CharBuffer decoded = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(bytes);
            String text = decoded.toString();
            int replacements = 0;
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) == '\ufffd') replacements++;
            }
            return new Decoded(text, label, replacements);
        } catch (CharacterCodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public enum Stability {
        STABLE,
        APPENDED_DURING_READ,
        TRUNCATED_DURING_READ,
        REPLACED_DURING_READ,
        UNSTABLE,
        MISSING
    }

    public record Result(String text, String charset, int replacementCount,
                         boolean truncated, long bytesRead, long sourceBytes,
                         Stability stability) {
        public Result(String text, String charset, int replacementCount,
                      boolean truncated, long bytesRead, long sourceBytes) {
            this(text, charset, replacementCount, truncated, bytesRead, sourceBytes, Stability.STABLE);
        }
    }

    public record Decoded(String text, String charset, int replacementCount) { }
    private record Encoding(Charset bomCharset, int bomBytes) { }
    private record FileStamp(Object fileKey, long size, long modified) { }
}
