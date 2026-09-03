package com.thang.chargeops.station.service.support;

import com.thang.chargeops.common.enums.StationOperatingState;

/**
 * Effective operating status shared by driver and owner read models.
 */
public record StationOperatingStatus(
        boolean openNow,
        StationOperatingState state,
        boolean scheduleConfigured
) {
    public static StationOperatingStatus open() {
        return new StationOperatingStatus(true, StationOperatingState.OPEN, true);
    }

    public static StationOperatingStatus unavailableByPlatform(boolean scheduleConfigured) {
        return new StationOperatingStatus(
                false,
                StationOperatingState.UNAVAILABLE_BY_PLATFORM,
                scheduleConfigured
        );
    }

    public static StationOperatingStatus pausedByOwner(boolean scheduleConfigured) {
        return new StationOperatingStatus(
                false,
                StationOperatingState.PAUSED_BY_OWNER,
                scheduleConfigured
        );
    }

    public static StationOperatingStatus maintenance(boolean scheduleConfigured) {
        return new StationOperatingStatus(
                false,
                StationOperatingState.MAINTENANCE,
                scheduleConfigured
        );
    }

    public static StationOperatingStatus closedBySchedule() {
        return new StationOperatingStatus(
                false,
                StationOperatingState.CLOSED_BY_SCHEDULE,
                true
        );
    }

    public static StationOperatingStatus scheduleNotConfigured() {
        return new StationOperatingStatus(
                false,
                StationOperatingState.SCHEDULE_NOT_CONFIGURED,
                false
        );
    }
}
