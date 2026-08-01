package com.mccompanion.terminal.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedTextReaderTest {
    @TempDir Path temp;

    @Test
    void readsBomAndStrictUtf8WithoutWarnings() throws Exception {
        Path log = temp.resolve("latest.log");
        byte[] text = "loaded minecraft_ai_companion\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[text.length + 3];
        bytes[0] = (byte) 0xef; bytes[1] = (byte) 0xbb; bytes[2] = (byte) 0xbf;
        System.arraycopy(text, 0, bytes, 3, text.length);
        Files.write(log, bytes);

        var result = BoundedTextReader.readTail(log, 4096);

        assertEquals("UTF-8", result.charset());
        assertEquals(0, result.replacementCount());
        assertTrue(result.text().contains("minecraft_ai_companion"));
        assertFalse(result.truncated());
    }

    @Test
    void fallsBackToGb18030ForRealWindowsLogs() throws Exception {
        Path log = temp.resolve("latest.log");
        Files.write(log, "[错误] 已加载 minecraft_ai_companion\n".getBytes(Charset.forName("GB18030")));

        var result = BoundedTextReader.readTail(log, 4096);

        assertEquals("GB18030", result.charset());
        assertEquals(0, result.replacementCount());
        assertTrue(result.text().contains("minecraft_ai_companion"));
    }

    @Test
    void replacesUndecodableBytesAndKeepsBoundedEvidence() throws Exception {
        Path log = temp.resolve("latest.log");
        Files.write(log, new byte[] {'[', 'E', 'R', 'R', 'O', 'R', ']', ' ', (byte) 0xff, '\n'});

        var result = BoundedTextReader.readTail(log, 4096);

        assertEquals("UTF-8-REPLACEMENT", result.charset());
        assertTrue(result.replacementCount() > 0);
        assertTrue(result.text().contains("ERROR"));
    }

    @Test
    void readsOnlyCompleteTailLinesWhenBounded() throws Exception {
        Path log = temp.resolve("latest.log");
        Files.writeString(log, "private-prefix\nfirst complete\nsecond complete\n", StandardCharsets.UTF_8);

        var result = BoundedTextReader.readTail(log, 30);

        assertTrue(result.truncated());
        assertFalse(result.text().contains("private-prefix"));
        assertTrue(result.text().contains("second complete"));
    }
}
