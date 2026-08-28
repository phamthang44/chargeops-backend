package com.thang.chargeops.infra.identity;

import com.thang.chargeops.common.enums.Role;

import java.util.Set;

/**
 * Port for reading account-level platform roles from the external identity provider.
 * Domain services depend on this interface instead of depending directly on Keycloak APIs.
 * Station-scoped staff authorization belongs to station_staff_assignments in the database.
 */
public interface IdentityRoleService {

    Set<Role> getRoles(String identityUserId);
}
