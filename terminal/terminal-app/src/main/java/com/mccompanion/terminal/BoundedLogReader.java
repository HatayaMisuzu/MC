package com.mccompanion.terminal;

import com.mccompanion.terminal.diagnostics.BoundedTextReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Reads a bounded mixed-encoding window without loading an entire long-running log into memory. */
final class BoundedLogReader {
    private static final int MAX_BYTES = 256 * 1024;

    static Result read(Path file, long offset, int maxLines) throws IOException {
        Result last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            FileStamp before = stamp(file);
            if (before == null) return missing();
            Result read = readOnce(file, offset, maxLines);
            FileStamp after = stamp(file);
            BoundedTextReader.Stability stability = classify(before, after);
            last = read.withStability(stability);
            if (stability == BoundedTextReader.Stability.STABLE || attempt == 1) return last;
        }
        return last == null ? missing() : last;
    }

    private static Result readOnce(Path file, long offset, int maxLines) throws IOException {
        if (!Files.isRegularFile(file)) return missing();
        long size = Files.size(file);
        Charset bom = BoundedTextReader.detectBomCharset(file);
        int bomBytes = BoundedTextReader.detectBomBytes(file);
        boolean utf16 = isUtf16(bom);
        boolean reset = offset < 0 || offset > size;
        String resetReason = offset < 0 ? "INITIAL" : offset > size ? "FILE_TRUNCATED_OR_ROTATED" : "NONE";
        long start = reset ? Math.max(0, size - MAX_BYTES) : offset;
        if (reset && utf16 && start > bomBytes && ((start - bomBytes) & 1L) != 0) start--;
        int length = (int) Math.min(MAX_BYTES, Math.max(0, size - start));
        if (length == 0) return new Result(List.of(), start, reset,
                "NONE", 0, start > 0, resetReason, BoundedTextReader.Stability.STABLE);
        byte[] bytes = readBytes(file, start, length);
        int from = reset && start > 0 ? firstCompleteLine(bytes, bom) : 0;
        int complete = lastCompleteBoundary(bytes, from, bom);
        long nextOffset = start + complete;
        if (complete <= from) return new Result(List.of(), nextOffset, reset,
                "NONE", 0, start > 0, resetReason, BoundedTextReader.Stability.STABLE);
        byte[] completeBytes = java.util.Arrays.copyOfRange(bytes, from, complete);
        BoundedTextReader.Decoded decoded = bom == null
                ? BoundedTextReader.decode(completeBytes, false)
                : BoundedTextReader.decode(completeBytes, bom, start == 0 && from == 0);
        String[] split = decoded.text().split("\\R", -1);
        int count = split.length;
        if (count > 0 && split[count - 1].isEmpty()) count--;
        int first = Math.max(0, count - maxLines);
        List<String> lines = new ArrayList<>(count - first);
        for (int index = first; index < count; index++) lines.add(split[index]);
        return new Result(List.copyOf(lines), nextOffset, reset, decoded.charset(),
                decoded.replacementCount(), start > 0, resetReason, BoundedTextReader.Stability.STABLE);
    }

    private static Result missing() {
        return new Result(List.of(), 0, false, "NONE", 0, false, "NONE",
                BoundedTextReader.Stability.MISSING);
    }

    private static FileStamp stamp(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        return new FileStamp(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime().toMillis());
    }

    private static BoundedTextReader.Stability classify(FileStamp before, FileStamp after) {
        if (after == null) return BoundedTextReader.Stability.REPLACED_DURING_READ;
        if (before.fileKey() != null && after.fileKey() != null
                && !before.fileKey().equals(after.fileKey())) {
            return BoundedTextReader.Stability.REPLACED_DURING_READ;
        }
        if (after.size() > before.size()) return BoundedTextReader.Stability.APPENDED_DURING_READ;
        if (after.size() < before.size()) return BoundedTextReader.Stability.TRUNCATED_DURING_READ;
        if (after.modified() != before.modified()) return BoundedTextReader.Stability.UNSTABLE;
        return BoundedTextReader.Stability.STABLE;
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
            return buffer.position() == bytes.length ? bytes
                    : java.util.Arrays.copyOf(bytes, buffer.position());
        }
    }

    private static int firstCompleteLine(byte[] bytes, Charset charset) {
        if (isUtf16(charset)) {
            boolean little = charset.equals(StandardCharsets.UTF_16LE);
            for (int index = 0; index + 1 < bytes.length; index += 2) {
                if ((little && bytes[index] == 0x0a && bytes[index + 1] == 0x00)
                        || (!little && bytes[index] == 0x00 && bytes[index + 1] == 0x0a)) return index + 2;
            }
            return bytes.length;
        }
        for (int index = 0; index < bytes.length; index++) if (bytes[index] == '\n') return index + 1;
        return bytes.length;
    }

    private static int lastCompleteBoundary(byte[] bytes, int from, Charset charset) {
        int last = from;
        if (isUtf16(charset)) {
            boolean little = charset.equals(StandardCharsets.UTF_16LE);
            for (int index = from; index + 1 < bytes.length; index += 2) {
                if ((little && bytes[index] == 0x0a && bytes[index + 1] == 0x00)
                        || (!little && bytes[index] == 0x00 && bytes[index + 1] == 0x0a)) last = index + 2;
            }
        } else {
            for (int index = from; index < bytes.length; index++) if (bytes[index] == '\n') last = index + 1;
        }
        return last;
    }

    private static boolean isUtf16(Charset charset) {
        return charset != null && (charset.equals(StandardCharsets.UTF_16LE)
                || charset.equals(StandardCharsets.UTF_16BE));
    }

    record Result(List<String> lines, long nextOffset, boolean reset, String charset,
                  int replacementCount, boolean truncated, String resetReason,
                  BoundedTextReader.Stability stability) {
        Result(List<String> lines, long nextOffset, boolean reset, String charset,
               int replacementCount, boolean truncated, String resetReason) {
            this(lines, nextOffset, reset, charset, replacementCount, truncated, resetReason,
                    BoundedTextReader.Stability.STABLE);
        }

        Result withStability(BoundedTextReader.Stability value) {
            return new Result(lines, nextOffset, reset, charset, replacementCount,
                    truncated, resetReason, value);
        }
    }

    private record FileStamp(Object fileKey, long size, long modified) { }
}
