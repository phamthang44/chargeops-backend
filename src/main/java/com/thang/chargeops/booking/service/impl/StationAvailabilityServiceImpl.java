package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.policy.BookingTimePolicy;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.mapper.StationAvailabilityMapper;
import com.thang.chargeops.booking.projection.BookingTimeRangeProjection;
import com.thang.chargeops.booking.service.StationAvailabilityService;
import com.thang.chargeops.booking.service.model.StationAvailabilitySnapshot;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.dto.station.filter.StationAvailabilityQuery;
import com.thang.chargeops.station.dto.station.response.StationAvailabilityResponse;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationBookingSetting;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.policy.ConnectorBookabilityPolicy;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.repository.StationBookingSettingsRepository;
import com.thang.chargeops.station.service.StationPricingService;
import com.thang.chargeops.station.service.model.OperatingWindow;
import com.thang.chargeops.station.service.model.StationPriceRange;
import com.thang.chargeops.station.service.support.StationOperatingHoursResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StationAvailabilityServiceImpl implements StationAvailabilityService {

    private static final ZoneId SYSTEM_ZONE_ID =
            ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);
    private static final Set<BookingStatus> BLOCKING_STATUSES = Set.copyOf(
            EnumSet.of(
                    BookingStatus.PENDING,
                    BookingStatus.CONFIRMED,
                    BookingStatus.CHECKED_IN,
                    BookingStatus.CHARGING
            )
    );

    private final ConnectorRepository connectorRepository;
    private final ConnectorBookabilityPolicy connectorBookabilityPolicy;
    private final BookingRepository bookingRepository;
    private final StationBookingSettingsRepository bookingSettingsRepository;
    private final StationOperatingHoursResolver operatingHoursResolver;
    private final StationPricingService stationPricingService;
    private final StationAvailabilityMapper availabilityMapper;
    private final BookingTimePolicy bookingTimePolicy;
    private final BookingPolicyConfig bookingPolicyConfig;
    private final Clock applicationClock;

    @Override
    @Transactional(readOnly = true)
    public StationAvailabilityResponse getAvailability(
            UUID stationId,
            StationAvailabilityQuery query
    ) {
        Instant generatedAt = applicationClock.instant();
        bookingTimePolicy.validateBookingDate(query.getDate(), generatedAt);

        Connector connector = requireStationConnector(
                stationId,
                query.getConnectorId()
        );
        connectorBookabilityPolicy.requireBookableForNewBooking(
                connector,
                generatedAt
        );

        Instant earliestStartAt = bookingTimePolicy.earliestStartAt(generatedAt);
        Instant dayStart = query.getDate()
                .atStartOfDay(SYSTEM_ZONE_ID)
                .toInstant();
        Instant dayEnd = query.getDate()
                .plusDays(1)
                .atStartOfDay(SYSTEM_ZONE_ID)
                .toInstant();

        int maxDurationMinutes = bookingPolicyConfig.getMaxDurationMinutes();
        int durationStepMinutes = bookingPolicyConfig.getDurationStepMinutes();
        Instant coverageStartAt = dayStart;
        Instant coverageEndAt = dayEnd.plus(Duration.ofMinutes(maxDurationMinutes));

        Station station = connector.getChargePoint().getStation();
        StationOperatingSchedule schedule = operatingHoursResolver
                .findActiveSchedule(stationId, generatedAt);
        List<OperatingWindow> operatingWindows = operatingHoursResolver
                .resolveOperatingWindows(
                        schedule,
                        query.getDate(),
                        coverageStartAt,
                        coverageEndAt
                );
        List<BookingTimeRangeProjection> busyRanges = bookingRepository
                .findBlockingRanges(
                        connector.getId(),
                        coverageStartAt,
                        coverageEndAt,
                        generatedAt,
                        BLOCKING_STATUSES
                );
        StationBookingSetting settings = bookingSettingsRepository
                .findByStationId(stationId)
                .orElseThrow(() -> new AppException(
                        BookingErrorCode.PRICING_NOT_CONFIGURED,
                        stationId
                ));
        int minDurationMinutes = Math.max(
                bookingPolicyConfig.getMinDurationMinutes(),
                settings.getMinDurationMinutes()
        );
        List<StationPriceRange> priceRanges = stationPricingService
                .resolvePriceRanges(
                        stationId,
                        generatedAt,
                        coverageStartAt,
                        coverageEndAt
                );

        String policyVersion = bookingPolicyConfig.toPolicyResponse().policyVersion();
        PriceBasis pricingEstimateParameters = PriceBasis.fixedPackage(connector.getPowerKw());

        return availabilityMapper.toResponse(
                StationAvailabilitySnapshot.builder()
                        .stationId(stationId)
                        .connectorId(connector.getId())
                        .date(query.getDate())
                        .generatedAt(generatedAt)
                        .earliestStartAt(earliestStartAt)
                        .dayStart(dayStart)
                        .dayEnd(dayEnd)
                        .coverageStartAt(coverageStartAt)
                        .coverageEndAt(coverageEndAt)
                        .minDurationMinutes(minDurationMinutes)
                        .durationStepMinutes(durationStepMinutes)
                        .maxDurationMinutes(maxDurationMinutes)
                        .settings(settings)
                        .operatingWindows(operatingWindows)
                        .busyRanges(busyRanges)
                        .priceRanges(priceRanges)
                        .policyVersion(policyVersion)
                        .pricingEstimateParameters(pricingEstimateParameters)
                        .build()
        );
    }

    private Connector requireStationConnector(UUID stationId, UUID connectorId) {
        return connectorRepository.findByIdWithChargePointAndStation(connectorId)
                .filter(connector -> connector.getChargePoint()
                        .getStation()
                        .getId()
                        .equals(stationId))
                .orElseThrow(() -> new AppException(
                        StationErrorCode.CONNECTOR_NOT_FOUND,
                        connectorId
                ));
    }
}
