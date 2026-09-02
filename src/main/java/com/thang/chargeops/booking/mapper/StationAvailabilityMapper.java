package com.thang.chargeops.booking.mapper;

import com.thang.chargeops.booking.projection.BookingTimeRangeProjection;
import com.thang.chargeops.booking.service.model.StationAvailabilitySnapshot;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.station.dto.station.response.StationAvailabilityResponse;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class StationAvailabilityMapper {

    public StationAvailabilityResponse toResponse(StationAvailabilitySnapshot snapshot) {
        return new StationAvailabilityResponse(
                snapshot.stationId(),
                snapshot.connectorId(),
                snapshot.date(),
                SystemConstant.SYSTEM_REGION_TIMEZONE,
                snapshot.generatedAt(),
                snapshot.settings().getMinDurationMinutes(),
                snapshot.settings().getDurationStepMinutes(),
                snapshot.settings().getMaxDurationMinutes(),
                snapshot.operatingWindows().stream()
                        .map(window -> new StationAvailabilityResponse.TimeRangeResponse(
                                window.startAt(),
                                window.endAt()
                        ))
                        .toList(),
                snapshot.busyRanges().stream()
                        .map(range -> clippedBusyRange(
                                range,
                                snapshot.dayStart(),
                                snapshot.dayEnd()
                        ))
                        .filter(range -> range.startAt().isBefore(range.endAt()))
                        .toList(),
                snapshot.priceRanges().stream()
                        .map(range -> new StationAvailabilityResponse.PriceRangeResponse(
                                range.startAt(),
                                range.endAt(),
                                range.rateVndPerKwh(),
                                range.periodCode()
                        ))
                        .toList()
        );
    }

    private StationAvailabilityResponse.TimeRangeResponse clippedBusyRange(
            BookingTimeRangeProjection range,
            Instant dayStart,
            Instant dayEnd
    ) {
        Instant startAt = range.getStartAt().isBefore(dayStart)
                ? dayStart
                : range.getStartAt();
        Instant endAt = range.getEndAt().isAfter(dayEnd)
                ? dayEnd
                : range.getEndAt();
        return new StationAvailabilityResponse.TimeRangeResponse(startAt, endAt);
    }
}
