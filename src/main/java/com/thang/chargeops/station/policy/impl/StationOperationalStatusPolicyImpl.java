package com.thang.chargeops.station.policy.impl;

import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.StationOperationalStatusPolicy;
import com.thang.chargeops.station.policy.StationVisibilityPolicy;
import com.thang.chargeops.station.repository.StationOperatingScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class StationOperationalStatusPolicyImpl implements StationOperationalStatusPolicy {

    private final StationVisibilityPolicy visibilityPolicy;
    private final StationOperatingScheduleRepository scheduleRepository;

    @Override
    public void requireCanChange(
            Station station,
            StationOperationalStatus targetStatus,
            String reason,
            Instant at
    ) {
        if (targetStatus == null) {
            throw new AppException(StationErrorCode.STATION_OPERATIONAL_STATUS_REQUIRED);
        }

        if (targetStatus != StationOperationalStatus.OPERATING) {
            if (reason == null || reason.isBlank()) {
                throw new AppException(
                        StationErrorCode.STATION_OPERATIONAL_STATUS_REASON_REQUIRED
                );
            }
            return;
        }

        if (!visibilityPolicy.isVisibleToDrivers(station, at)) {
            throw new AppException(
                    StationErrorCode.STATION_NOT_ELIGIBLE_FOR_NEW_BUSINESS
            );
        }
        if (scheduleRepository.findActiveByStationId(station.getId(), at).isEmpty()) {
            throw new AppException(StationErrorCode.STATION_OPERATING_SCHEDULE_REQUIRED);
        }
    }
}
