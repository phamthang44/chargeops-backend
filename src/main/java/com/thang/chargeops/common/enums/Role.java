package com.thang.chargeops.common.enums;

/**
 * Platform roles. A user is assigned exactly one role at registration (BR-ACC-01).
 * These map to Keycloak realm roles and drive role-based access control.
 */
public enum Role {
    DRIVER,
    OWNER,
    ADMIN,
    STAFF
}
