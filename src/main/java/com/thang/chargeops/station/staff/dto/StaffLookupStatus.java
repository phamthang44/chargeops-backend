package com.thang.chargeops.station.staff.dto;

/**
 * Result of evaluating an exact-email lookup for the assign-staff UX.
 */
public enum StaffLookupStatus {
    ELIGIBLE,
    NOT_FOUND,
    SELF_ASSIGNMENT,
    ACCOUNT_INACTIVE,
    ROLE_NOT_ALLOWED,
    ALREADY_ASSIGNED
}
