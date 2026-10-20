package com.anode.factory.identity;

import java.util.EnumSet;
import java.util.Set;

/** Roles granted from identity-provider groups (anode.security.roles.mapping). */
public enum Role {
    ADMIN(EnumSet.allOf(Permission.class)),
    DEVELOPER(EnumSet.of(
            Permission.WORK_ORDER_READ, Permission.WORK_ORDER_WRITE, Permission.APPROVAL_DECIDE,
            Permission.SETTINGS_READ)),
    VIEWER(EnumSet.of(Permission.WORK_ORDER_READ, Permission.SETTINGS_READ)),
    /** Machine role of the workers, never granted to people. */
    WORKER(EnumSet.of(Permission.RUN_EXECUTE));

    @SuppressWarnings("ImmutableEnumChecker") // an unmodifiable copy
    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    /** The Spring Security authority of this role. */
    public String authority() {
        return "ROLE_" + name();
    }
}
