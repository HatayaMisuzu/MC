package com.mccompanion.terminal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads a bounded UTF-8 window without loading an entire long-running log into memory. */
final class BoundedLogReader {
    private static final int MAX_BYTES = 256 * 1024;

    static Result read(Path file, long offset, int maxLines) throws IOException {
        if (!Files.isRegularFile(file)) return new Result(List.of(), 0, false);
        long size = Files.size(file);
        boolean reset = offset < 0 || offset > size;
        long start = reset ? Math.max(0, size - MAX_BYTES) : offset;
        int length = (int) Math.min(MAX_BYTES, Math.max(0, size - start));
        if (length == 0) return new Result(List.of(), start, reset);
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
        if (complete <= from) return new Result(List.of(), nextOffset, reset);
        String text = new String(bytes, from, complete - from, StandardCharsets.UTF_8);
        String[] split = text.split("\\R", -1);
        int count = split.length;
        if (count > 0 && split[count - 1].isEmpty()) count--;
        int first = Math.max(0, count - maxLines);
        List<String> lines = new ArrayList<>(count - first);
        for (int index = first; index < count; index++) lines.add(split[index]);
        return new Result(List.copyOf(lines), nextOffset, reset);
    }

    record Result(List<String> lines, long nextOffset, boolean reset) { }
}
