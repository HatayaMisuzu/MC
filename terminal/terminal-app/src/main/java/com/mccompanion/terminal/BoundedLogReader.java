package com.mccompanion.terminal;

import com.mccompanion.terminal.diagnostics.BoundedTextReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads a bounded mixed-encoding window without loading an entire long-running log into memory. */
final class BoundedLogReader {
    private static final int MAX_BYTES = 256 * 1024;

    static Result read(Path file, long offset, int maxLines) throws IOException {
        if (!Files.isRegularFile(file)) return new Result(List.of(), 0, false,
                "NONE", 0, false, "NONE");
        long size = Files.size(file);
        boolean reset = offset < 0 || offset > size;
        String resetReason = offset < 0 ? "INITIAL" : offset > size ? "FILE_TRUNCATED_OR_ROTATED" : "NONE";
        long start = reset ? Math.max(0, size - MAX_BYTES) : offset;
        int length = (int) Math.min(MAX_BYTES, Math.max(0, size - start));
        if (length == 0) return new Result(List.of(), start, reset,
                "NONE", 0, start > 0, resetReason);
        byte[] bytes = new byte[length];
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
        }
        int from = 0;
        if (start > 0 && reset) {
            while (from < bytes.length && bytes[from] != '\n') from++;
            if (from < bytes.length) from++;
        }
        int complete = bytes.length;
        if (start + bytes.length < size || bytes[bytes.length - 1] != '\n') {
            while (complete > from && bytes[complete - 1] != '\n') complete--;
        }
        long nextOffset = start + complete;
        if (complete <= from) return new Result(List.of(), nextOffset, reset,
                "NONE", 0, start > 0, resetReason);
        byte[] completeBytes = java.util.Arrays.copyOfRange(bytes, from, complete);
        BoundedTextReader.Decoded decoded = BoundedTextReader.decode(completeBytes, start == 0 && from == 0);
        String text = decoded.text();
        String[] split = text.split("\\R", -1);
        int count = split.length;
        if (count > 0 && split[count - 1].isEmpty()) count--;
        int first = Math.max(0, count - maxLines);
        List<String> lines = new ArrayList<>(count - first);
        for (int index = first; index < count; index++) lines.add(split[index]);
        return new Result(List.copyOf(lines), nextOffset, reset, decoded.charset(),
                decoded.replacementCount(), start > 0, resetReason);
    }

    record Result(List<String> lines, long nextOffset, boolean reset, String charset,
                  int replacementCount, boolean truncated, String resetReason) { }
}
