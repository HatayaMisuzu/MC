package com.mccompanion.runtime.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class PairingTokenStore {
    private static final Pattern VALID_TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private final Path tokenPath;
    private final SecureRandom random;

    public PairingTokenStore(Path tokenPath) {
        this(tokenPath, new SecureRandom());
    }

    PairingTokenStore(Path tokenPath, SecureRandom random) {
        this.tokenPath = tokenPath.toAbsolutePath().normalize();
        this.random = random;
    }

    public String loadOrCreate() throws IOException {
        Path parent = tokenPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(tokenPath, LinkOption.NOFOLLOW_LINKS)) {
            return readExisting();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Path temporary = Files.createTempFile(parent, ".pairing-", ".token");
        try {
            applyOwnerOnlyPermissions(temporary);
            Files.writeString(temporary, token + System.lineSeparator(), StandardCharsets.US_ASCII,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, tokenPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, tokenPath);
            } catch (java.nio.file.FileAlreadyExistsException race) {
                Files.deleteIfExists(temporary);
                return readExisting();
            }
            applyOwnerOnlyPermissions(tokenPath);
            return token;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String readExisting() throws IOException {
        if (Files.isSymbolicLink(tokenPath)) {
            throw new IOException("Pairing token file must not be a symbolic link");
        }
        if (!Files.isRegularFile(tokenPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pairing token path is not a regular file");
        }
        applyOwnerOnlyPermissions(tokenPath);
        String token = Files.readString(tokenPath, StandardCharsets.US_ASCII).trim();
        if (!VALID_TOKEN.matcher(token).matches()) {
            throw new IOException("Pairing token file has an invalid format");
        }
        return token;
    }

    private static void applyOwnerOnlyPermissions(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            java.io.File file = path.toFile();
            if ((!file.setReadable(false, false) || !file.setWritable(false, false)
                    || !file.setReadable(true, true) || !file.setWritable(true, true)
                    || !file.setExecutable(false, false)) && !Files.isReadable(path)) {
                throw new IOException("Unable to restrict pairing token file permissions");
            }
        }
    }

    public static boolean matches(String expected, String candidate) {
        if (expected == null || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }
}
