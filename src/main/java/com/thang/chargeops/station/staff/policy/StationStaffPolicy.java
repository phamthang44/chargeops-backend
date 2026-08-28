package com.thang.chargeops.station.staff.policy;

import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.staff.entity.StationStaffAssignment;

import java.util.Set;
import java.util.UUID;

public interface StationStaffPolicy {

    /**
     * Validates a command that reads the staff assignments of one station.
     *
     * <p>The implementation must require that the current profile owns the station. Station and
     * license lifecycle state must not block viewing assignment history, because ownership and
     * staff audit data remain valid even when the station is suspended or its license expires.</p>
     */
    void requireOwnerCanManageStaff(Station station, UUID currentOwnerProfileId);

    /**
     * Validates the complete assign-staff command after the service has resolved the submitted
     * normalized email to an existing profile and checked for a current ACTIVE assignment.
     *
     * <p>Mandatory validations:</p>
     * <ol>
     *     <li>The current profile owns the target station.</li>
     *     <li>The target station is ACTIVE. License status is not an ownership check.</li>
     *     <li>The candidate profile is ACTIVE and is not the current owner.</li>
     *     <li>The candidate has the DRIVER platform role and has neither OWNER nor ADMIN role.</li>
     *     <li>The candidate has no ACTIVE assignment at any station, including this station.</li>
     * </ol>
     *
     * <p>The database partial unique index remains the final concurrency guard. Implementations
     * must return typed application errors and must not rely on this pre-check alone.</p>
     */
    void requireCanAssignStaff(
            Station station,
            UUID currentOwnerProfileId,
            UserProfile candidate,
            Set<Role> candidateRoles,
            boolean hasActiveAssignment
    );

    /**
     * Validates a revoke command before the assignment is changed to REVOKED.
     *
     * <p>Mandatory validations:</p>
     * <ol>
     *     <li>The assignment belongs to the station identified by the request path.</li>
     *     <li>The current profile owns that station.</li>
     *     <li>The assignment is currently ACTIVE.</li>
     * </ol>
     *
     * <p>Revocation must remain available regardless of station or license status.</p>
     */
    void requireCanRevokeStaff(
            StationStaffAssignment assignment,
            UUID requestedStationId,
            UUID currentOwnerProfileId
    );
}
