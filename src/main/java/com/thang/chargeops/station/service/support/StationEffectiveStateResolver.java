package com.thang.chargeops.station.service.support;

import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Combines platform visibility, owner operating intent and weekly hours into
 * the effective state exposed to clients.
 */
@Component
@RequiredArgsConstructor
public class StationEffectiveStateResolver {

    private final StationOperatingHoursResolver operatingHoursResolver;

    public StationOperatingStatus resolve(
            boolean visibleToDrivers,
            StationOperationalStatus operationalStatus,
            StationOperatingSchedule schedule,
            Instant at
    ) {
        boolean scheduleConfigured = schedule != null
                && at != null
                && schedule.isActive(at);

        if (!visibleToDrivers) {
            return StationOperatingStatus.unavailableByPlatform(scheduleConfigured);
        }
        if (operationalStatus == StationOperationalStatus.PAUSED) {
            return StationOperatingStatus.pausedByOwner(scheduleConfigured);
        }
        if (operationalStatus == StationOperationalStatus.MAINTENANCE) {
            return StationOperatingStatus.maintenance(scheduleConfigured);
        }
        return operatingHoursResolver.resolveStatus(schedule, at);
    }
}
