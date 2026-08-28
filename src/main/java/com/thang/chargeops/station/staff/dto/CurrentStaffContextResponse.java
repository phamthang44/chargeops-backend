package com.thang.chargeops.station.staff.dto;

import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;

import java.util.UUID;

/**
 * DB-backed staff context used after authentication.
 * Keycloak intentionally does not carry station-scoped staff authorization.
 */
public record CurrentStaffContextResponse(
        boolean staff,
        UUID assignmentId,
        StaffAssignmentStatus assignmentStatus,
        StationSummary station
) {
    public static CurrentStaffContextResponse notAssigned() {
        return new CurrentStaffContextResponse(false, null, null, null);
    }

    public record StationSummary(
            UUID id,
            String stationCode,
            String name
    ) {
    }
}
