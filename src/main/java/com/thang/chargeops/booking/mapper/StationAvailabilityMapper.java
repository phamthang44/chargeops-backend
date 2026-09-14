package com.thang.chargeops.booking.mapper;

import com.thang.chargeops.booking.projection.BookingTimeRangeProjection;
import com.thang.chargeops.booking.service.model.StationAvailabilitySnapshot;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.station.dto.station.response.StationAvailabilityResponse;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class StationAvailabilityMapper {

    public StationAvailabilityResponse toResponse(StationAvailabilitySnapshot snapshot) {
        return new StationAvailabilityResponse(
                snapshot.stationId(),
                snapshot.connectorId(),
                snapshot.date(),
                SystemConstant.SYSTEM_REGION_TIMEZONE,
                snapshot.generatedAt(),
                snapshot.earliestStartAt(),
                snapshot.coverageStartAt(),
                snapshot.coverageEndAt(),
                snapshot.minDurationMinutes(),
                snapshot.durationStepMinutes(),
                snapshot.maxDurationMinutes(),
                snapshot.operatingWindows().stream()
                        .map(window -> new StationAvailabilityResponse.TimeRangeResponse(
                                window.startAt(),
                                window.endAt()
                        ))
                        .toList(),
                snapshot.busyRanges().stream()
                        .map(range -> clippedBusyRange(
                                range,
                                snapshot.coverageStartAt(),
                                snapshot.coverageEndAt()
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
                        .toList(),
                snapshot.policyVersion(),
                snapshot.pricingEstimateParameters()
        );
    }

    private StationAvailabilityResponse.TimeRangeResponse clippedBusyRange(
            BookingTimeRangeProjection range,
            Instant rangeStart,
            Instant rangeEnd
    ) {
        Instant startAt = range.getStartAt().isBefore(rangeStart)
                ? rangeStart
                : range.getStartAt();
        Instant endAt = range.getEndAt().isAfter(rangeEnd)
                ? rangeEnd
                : range.getEndAt();
        return new StationAvailabilityResponse.TimeRangeResponse(startAt, endAt);
    }
}
