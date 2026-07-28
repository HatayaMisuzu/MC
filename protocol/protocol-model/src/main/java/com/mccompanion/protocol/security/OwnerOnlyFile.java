package com.mccompanion.protocol.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        String userName = System.getProperty("user.name", "").strip();
        if (userName.isEmpty()) throw new IOException("Current user identity is unavailable");
        UserPrincipal current;
        try {
            current = file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(userName);
        } catch (Exception failure) {
            throw new IOException("Current user principal could not be resolved", failure);
        }
        if (!samePrincipal(owner, current)) {
            throw new IOException("Sensitive local state is not owned by the current user");
        }
    }

    private static boolean samePrincipal(UserPrincipal left, UserPrincipal right) {
        return left.equals(right);
    }
}
