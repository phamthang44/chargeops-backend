package com.thang.chargeops.common.enums;

/**
 * Explains the station's effective operating state at one instant.
 * Station lifecycle/driver eligibility remain separate concerns.
 */
public enum StationOperatingState {
    UNAVAILABLE_BY_PLATFORM,
    PAUSED_BY_OWNER,
    MAINTENANCE,
    OPEN,
    CLOSED_BY_SCHEDULE,
    SCHEDULE_NOT_CONFIGURED
}
