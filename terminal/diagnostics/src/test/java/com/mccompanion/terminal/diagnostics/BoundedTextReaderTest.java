package com.mccompanion.terminal.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertEquals(BoundedTextReader.Stability.STABLE, result.stability());
    }

    @Test
    void readsUtf16LeAndBeBomLogs() throws Exception {
        for (Charset charset : List.of(StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            Path log = temp.resolve("latest-" + charset.name() + ".log");
            byte[] body = "first 中文\nsecond line\n".getBytes(charset);
            byte[] bytes = new byte[body.length + 2];
            if (charset.equals(StandardCharsets.UTF_16LE)) {
                bytes[0] = (byte) 0xff;
                bytes[1] = (byte) 0xfe;
            } else {
                bytes[0] = (byte) 0xfe;
                bytes[1] = (byte) 0xff;
            }
            System.arraycopy(body, 0, bytes, 2, body.length);
            Files.write(log, bytes);

            var result = BoundedTextReader.readTail(log, 4096);

            assertEquals(charset.name(), result.charset());
            assertEquals(0, result.replacementCount());
            assertTrue(result.text().contains("second line"));
            assertEquals(BoundedTextReader.Stability.STABLE, result.stability());
        }
    }

    @Test
    void preservesUtf16CodeUnitBoundariesForLargeTails() throws Exception {
        for (Charset charset : List.of(StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            Path log = temp.resolve("large-" + charset.name() + ".log");
            StringBuilder content = new StringBuilder();
            for (int index = 0; index < 12_000; index++) {
                content.append("line-").append(index).append(" 中文\n");
            }
            byte[] body = content.toString().getBytes(charset);
            byte[] bytes = new byte[body.length + 2];
            if (charset.equals(StandardCharsets.UTF_16LE)) {
                bytes[0] = (byte) 0xff;
                bytes[1] = (byte) 0xfe;
            } else {
                bytes[0] = (byte) 0xfe;
                bytes[1] = (byte) 0xff;
            }
            System.arraycopy(body, 0, bytes, 2, body.length);
            Files.write(log, bytes);

            var result = BoundedTextReader.readTail(log, 4096);

            assertTrue(result.truncated());
            assertTrue(result.text().contains("line-11999 中文"));
            assertFalse(result.text().contains("line-0 中文"));
            assertEquals(charset.name(), result.charset());
            assertEquals(0, result.replacementCount());
        }
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
    void replacesMalformedUtf16TailWithoutCrashing() throws Exception {
        Path log = temp.resolve("malformed-utf16.log");
        Files.write(log, new byte[] {(byte) 0xff, (byte) 0xfe, 'o', 0, 'k', 0, 0x41});

        var result = BoundedTextReader.readTail(log, 4096);

        assertEquals("UTF-16LE", result.charset());
        assertTrue(result.replacementCount() > 0);
        assertTrue(result.text().startsWith("ok"));
    }

    @Test
    void missingSourceIsReportedWithoutThrowing() throws Exception {
        var result = BoundedTextReader.readTail(temp.resolve("does-not-exist.log"), 4096);

        assertEquals(BoundedTextReader.Stability.MISSING, result.stability());
        assertEquals("NONE", result.charset());
        assertEquals("", result.text());
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
