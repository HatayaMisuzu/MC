package com.mccompanion.protocol.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Applies and verifies a fail-closed current-user-only file permission boundary. */
public final class OwnerOnlyFile {
    private static final Set<PosixFilePermission> POSIX_OWNER_RW = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private OwnerOnlyFile() {}

    /**
     * Atomically creates a new empty file and establishes the current-user-only boundary before
     * callers write sensitive data. This is intentionally separate from {@link #secure(Path)}:
     * elevated Windows tokens can assign Administrators as the default owner of a new file, while
     * an existing file with a foreign owner must remain fail-closed.
     */
    public static Path create(Path path, FileAttribute<?>... attributes) throws IOException {
        Path file = path.toAbsolutePath().normalize();
        Files.createFile(file, attributes);
        return secureCreated(file);
    }

    /**
     * Creates a new empty temporary file and establishes the current-user-only boundary before
     * returning it to the caller.
     */
    public static Path createTempFile(
            Path directory, String prefix, String suffix, FileAttribute<?>... attributes)
            throws IOException {
        Path file = Files.createTempFile(directory, prefix, suffix, attributes)
                .toAbsolutePath().normalize();
        return secureCreated(file);
    }

    public static void secure(Path path) throws IOException {
        Path file = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)) {
            throw new IOException("Sensitive local state must be a regular non-link file");
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            secureAcl(file, acl);
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            verifyCurrentOwner(file);
            Files.setPosixFilePermissions(file, POSIX_OWNER_RW);
            if (!Files.getPosixFilePermissions(file).equals(POSIX_OWNER_RW)) {
                throw new IOException("Unable to verify owner-only POSIX file permissions");
            }
            return;
        }
        throw new IOException("Owner-only file permissions are unsupported on this filesystem");
    }

    public static boolean isOwnerOnly(Path path) throws IOException {
        Path file = path.toAbsolutePath().normalize();
        verifyCurrentOwner(file);
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) return aclIsOwnerOnly(acl);
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        return posix != null && Files.getPosixFilePermissions(file).equals(POSIX_OWNER_RW);
    }

    private static void secureAcl(Path file, AclFileAttributeView acl) throws IOException {
        verifyCurrentOwner(file);
        UserPrincipal owner = Files.getOwner(file);
        AclEntry ownerEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        acl.setAcl(List.of(ownerEntry));
        if (!aclIsOwnerOnly(acl)) {
            throw new IOException("Unable to verify current-user-only Windows ACL");
        }
    }

    private static boolean aclIsOwnerOnly(AclFileAttributeView acl) throws IOException {
        UserPrincipal owner = acl.getOwner();
        List<AclEntry> entries = acl.getAcl();
        return !entries.isEmpty() && entries.stream().allMatch(entry ->
                entry.type() == AclEntryType.ALLOW && samePrincipal(owner, entry.principal()));
    }

    private static void verifyCurrentOwner(Path file) throws IOException {
        UserPrincipal owner = Files.getOwner(file);
        UserPrincipal current = currentPrincipal(file);
        if (!samePrincipal(owner, current)) {
            throw new IOException("Sensitive local state is not owned by the current user");
        }
    }

    private static Path secureCreated(Path file) throws IOException {
        boolean secured = false;
        try {
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)
                    || Files.size(file) != 0L) {
                throw new IOException("New sensitive local state must be an empty regular non-link file");
            }
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal current = currentPrincipal(file);
                acl.setOwner(current);
                if (!samePrincipal(acl.getOwner(), current)) {
                    throw new IOException("Unable to establish current-user ownership");
                }
            }
            secure(file);
            secured = true;
            return file;
        } finally {
            if (!secured) Files.deleteIfExists(file);
        }
    }

    private static UserPrincipal currentPrincipal(Path file) throws IOException {
        String userName = System.getProperty("user.name", "").strip();
        if (userName.isEmpty()) throw new IOException("Current user identity is unavailable");
        try {
            return file.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(userName);
        } catch (Exception failure) {
            throw new IOException("Current user principal could not be resolved", failure);
        }
    }

    private static boolean samePrincipal(UserPrincipal left, UserPrincipal right) {
        return left.equals(right);
    }
}
