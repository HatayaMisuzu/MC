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

/** Bounded, replacement-aware reader for Windows, Minecraft and third-party text logs. */
public final class BoundedTextReader {
    private static final Charset GB18030 = Charset.forName("GB18030");

    private BoundedTextReader() { }

    public static Result readTail(Path file, int maxBytes) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        if (!Files.isRegularFile(file)) return new Result("", "NONE", 0, false, 0, 0);
        long size = Files.size(file);
        long start = Math.max(0, size - maxBytes);
        int length = (int) Math.min(maxBytes, size - start);
        byte[] bytes = new byte[length];
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
        }
        int from = 0;
        if (start > 0) {
            while (from < bytes.length && bytes[from] != '\n') from++;
            if (from < bytes.length) from++;
        }
        byte[] bounded = java.util.Arrays.copyOfRange(bytes, from, bytes.length);
        Decoded decoded = decode(bounded, start == 0);
        return new Result(decoded.text(), decoded.charset(), decoded.replacementCount(),
                start > 0, bounded.length, size);
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
        ByteBuffer data = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        if (bomCharset != null) {
            return decodeReplacing(data, bomCharset, bomCharset.name());
        }
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

    public record Result(String text, String charset, int replacementCount,
                         boolean truncated, long bytesRead, long sourceBytes) { }

    public record Decoded(String text, String charset, int replacementCount) { }
}
