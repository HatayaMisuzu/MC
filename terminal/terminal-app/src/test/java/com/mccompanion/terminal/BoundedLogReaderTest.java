package com.mccompanion.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BoundedLogReaderTest {
    @TempDir Path temporary;

    @Test
    void readsBoundedTailThenOnlyAppendedCompleteLines() throws Exception {
        Path log = temporary.resolve("latest.log");
        StringBuilder large = new StringBuilder();
        for (int index = 0; index < 40_000; index++) {
            large.append("line-").append(index).append("-xxxxxxxx\n");
        }
        Files.writeString(log, large, StandardCharsets.UTF_8);

        BoundedLogReader.Result tail = BoundedLogReader.read(log, -1, 500);
        assertEquals(500, tail.lines().size());
        assertEquals("line-39999-xxxxxxxx", tail.lines().getLast());
        assertTrue(tail.reset());

        Files.writeString(log, "partial", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        BoundedLogReader.Result partial = BoundedLogReader.read(log, tail.nextOffset(), 500);
        assertTrue(partial.lines().isEmpty());
        assertEquals(tail.nextOffset(), partial.nextOffset());

        Files.writeString(log, "-done\nnext\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        BoundedLogReader.Result delta = BoundedLogReader.read(log, partial.nextOffset(), 500);
        assertEquals(java.util.List.of("partial-done", "next"), delta.lines());
        assertFalse(delta.reset());
        assertEquals("UTF-8", delta.charset());
        assertEquals(0, delta.replacementCount());
    }

    @Test
    void reportsGb18030AndRotationResetReason() throws Exception {
        Path log = temporary.resolve("gb.log");
        Files.write(log, "中文日志\n".getBytes(java.nio.charset.Charset.forName("GB18030")));
        BoundedLogReader.Result first = BoundedLogReader.read(log, -1, 10);
        assertEquals(java.util.List.of("中文日志"), first.lines());
        assertEquals("GB18030", first.charset());
        assertEquals("INITIAL", first.resetReason());
        BoundedLogReader.Result rotated = BoundedLogReader.read(log, 9999, 10);
        assertTrue(rotated.reset());
        assertEquals("FILE_TRUNCATED_OR_ROTATED", rotated.resetReason());
    }
}
