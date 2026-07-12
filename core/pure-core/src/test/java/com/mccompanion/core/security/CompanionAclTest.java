package com.mccompanion.core.security;

import com.mccompanion.core.failure.FailureCode;
import com.mccompanion.core.id.OwnerId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionAclTest {
    @Test
    void ownerOperatorAndDelegatesReceiveOnlyIntendedPermissions() {
        OwnerId owner = OwnerId.random();
        OwnerId controller = OwnerId.random();
        OwnerId viewer = OwnerId.random();
        CompanionAcl acl = new CompanionAcl(owner, Map.of(
                controller, Set.of(CompanionPermission.CONTROL),
                viewer, Set.of(CompanionPermission.VIEW)));

        assertTrue(acl.authorize(new AclPrincipal(owner, false), CompanionPermission.MANAGE).allowed());
        assertTrue(acl.authorize(new AclPrincipal(OwnerId.random(), true), CompanionPermission.MANAGE).allowed());
        assertTrue(acl.authorize(new AclPrincipal(controller, false), CompanionPermission.CONTROL).allowed());
        assertTrue(acl.authorize(new AclPrincipal(controller, false), CompanionPermission.VIEW).allowed());
        assertFalse(acl.authorize(new AclPrincipal(controller, false), CompanionPermission.MANAGE).allowed());
        assertFalse(acl.authorize(new AclPrincipal(viewer, false), CompanionPermission.CONTROL).allowed());
        assertEquals(FailureCode.UNAUTHORIZED,
                acl.authorize(new AclPrincipal(OwnerId.random(), false), CompanionPermission.VIEW).failureCode());
    }

    @Test
    void delegateConfigurationIsDeeplyImmutableAndCannotOverrideOwner() {
        OwnerId owner = OwnerId.random();
        OwnerId delegate = OwnerId.random();
        CompanionAcl acl = new CompanionAcl(owner, Map.of(delegate, Set.of(CompanionPermission.MANAGE)));

        assertThrows(UnsupportedOperationException.class,
                () -> acl.delegates().put(OwnerId.random(), Set.of(CompanionPermission.VIEW)));
        assertThrows(UnsupportedOperationException.class,
                () -> acl.delegates().get(delegate).add(CompanionPermission.VIEW));
        assertThrows(IllegalArgumentException.class,
                () -> new CompanionAcl(owner, Map.of(owner, Set.of(CompanionPermission.VIEW))));
        assertThrows(IllegalArgumentException.class,
                () -> new CompanionAcl(owner, Map.of(delegate, Set.of())));
    }
}
