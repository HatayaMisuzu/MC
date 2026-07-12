package com.mccompanion.core.security;

import com.mccompanion.core.id.OwnerId;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CompanionAcl {
    private final OwnerId ownerId;
    private final Map<OwnerId, Set<CompanionPermission>> delegates;

    public CompanionAcl(OwnerId ownerId, Map<OwnerId, Set<CompanionPermission>> delegates) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(delegates, "delegates");
        Map<OwnerId, Set<CompanionPermission>> copy = new HashMap<>();
        delegates.forEach((delegate, permissions) -> {
            Objects.requireNonNull(delegate, "delegate");
            Objects.requireNonNull(permissions, "permissions");
            if (delegate.equals(ownerId)) {
                throw new IllegalArgumentException("owner permissions are implicit and cannot be delegated");
            }
            if (permissions.isEmpty()) {
                throw new IllegalArgumentException("delegate permission set cannot be empty");
            }
            EnumSet<CompanionPermission> permissionCopy = EnumSet.copyOf(permissions);
            copy.put(delegate, Collections.unmodifiableSet(permissionCopy));
        });
        this.delegates = Collections.unmodifiableMap(copy);
    }

    public static CompanionAcl ownerOnly(OwnerId ownerId) {
        return new CompanionAcl(ownerId, Map.of());
    }

    public AccessDecision authorize(AclPrincipal principal, CompanionPermission permission) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(permission, "permission");
        if (principal.operator()) {
            return AccessDecision.allow("Server operator is authorized");
        }
        if (principal.playerId().equals(ownerId)) {
            return AccessDecision.allow("Companion owner is authorized");
        }
        Set<CompanionPermission> granted = delegates.getOrDefault(principal.playerId(), Set.of());
        if (granted.contains(permission)
                || granted.contains(CompanionPermission.MANAGE)
                || (permission == CompanionPermission.VIEW && granted.contains(CompanionPermission.CONTROL))) {
            return AccessDecision.allow("Delegated permission is authorized");
        }
        return AccessDecision.deny("Only the companion owner, an authorized delegate, or an operator may "
                + permission.name().toLowerCase() + " this companion");
    }

    public OwnerId ownerId() {
        return ownerId;
    }

    public Map<OwnerId, Set<CompanionPermission>> delegates() {
        return delegates;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CompanionAcl that
                && ownerId.equals(that.ownerId)
                && delegates.equals(that.delegates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerId, delegates);
    }
}
