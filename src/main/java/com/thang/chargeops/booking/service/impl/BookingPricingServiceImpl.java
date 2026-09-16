package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.policy.BookingTimePolicy;
import com.thang.chargeops.booking.dto.response.BookingPolicyResponse;
import com.thang.chargeops.booking.dto.request.PricePreviewRequest;
import com.thang.chargeops.booking.dto.response.PricePreviewResponse;
import com.thang.chargeops.booking.pricing.BookingPriceCalculator;
import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.pricing.PricePreview;
import com.thang.chargeops.booking.pricing.PriceSegment;
import com.thang.chargeops.booking.pricing.PriceSegmentMapper;
import com.thang.chargeops.booking.pricing.PricingVersionHelper;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingPricingService;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.policy.ConnectorBookabilityPolicy;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.repository.StationBookingSettingsRepository;
import com.thang.chargeops.station.entity.StationBookingSetting;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.service.support.StationOperatingHoursResolver;
import com.thang.chargeops.station.service.StationPricingService;
import com.thang.chargeops.station.service.model.StationPriceRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;


@Service
@RequiredArgsConstructor
public class BookingPricingServiceImpl implements BookingPricingService {

    private static final String CURRENCY = "VND";
    private static final String OVERLAP_WARNING_TEMPLATE =
            "You have an existing booking #%s at another station/connector overlapping this time (%s - %s).";
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
    private final StationPricingService stationPricingService;
    private final BookingPriceCalculator bookingPriceCalculator;
    private final BookingPolicyConfig bookingPolicyConfig;
    private final Clock applicationClock;
    private final BookingTimePolicy bookingTimePolicy;
    private final StationBookingSettingsRepository bookingSettingsRepository;
    private final StationOperatingHoursResolver operatingHoursResolver;
    private final CurrentProfileProvider currentProfileProvider;

    @Override
    @Transactional(readOnly = true)
    public PricePreviewResponse previewBookingPrice(PricePreviewRequest request) {
        Instant generatedAt = applicationClock.instant();
        UserProfile driver = currentProfileProvider.requireProfile();
        Connector connector = requireConnector(request.connectorId());
        return calculateCurrentPreview(
                driver,
                connector,
                request.startAt(),
                request.durationMin(),
                generatedAt
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PricePreviewResponse repriceUnderLock(UserProfile driver, Connector lockedConnector, Instant startAt, int durationMin, Instant decisionAt) {
        return calculateCurrentPreview(
                driver,
                lockedConnector,
                startAt,
                durationMin,
                decisionAt
        );
    }

    private Connector requireConnector(UUID connectorId) {
        return connectorRepository.findByIdWithChargePointAndStation(connectorId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.CONNECTOR_NOT_FOUND,
                        connectorId
                ));
    }

    private PricePreviewResponse calculateCurrentPreview(
            UserProfile driver,
            Connector connector,
            Instant startAt,
            int durationMin,
            Instant decisionAt
    ) {
        connectorBookabilityPolicy.requireBookableForNewBooking(
                connector,
                decisionAt
        );

        UUID stationId = connector.getChargePoint().getStation().getId();
        int stationMinDuration = bookingSettingsRepository.findByStationId(stationId)
                .map(StationBookingSetting::getMinDurationMinutes)
                .orElse(bookingPolicyConfig.getMinDurationMinutes());
        StationOperatingSchedule schedule = operatingHoursResolver.findActiveSchedule(stationId, decisionAt);
        Instant endAt = bookingTimePolicy.validate(
                startAt, durationMin, stationMinDuration, schedule, decisionAt);

        if (bookingRepository.existsOverlappingBooking(connector.getId(), startAt, endAt, decisionAt)) {
            throw new AppException(
                    BookingErrorCode.SLOT_UNAVAILABLE
            );
        }
        List<Booking> overlappingBookings = bookingRepository.findOverlappingDriverBookings(
                driver.getId(),
                connector.getId(),
                startAt,
                endAt,
                decisionAt,
                BLOCKING_STATUSES
        );
        List<String> overlapWarnings = overlappingBookings.stream()
                .map(b -> String.format(
                        OVERLAP_WARNING_TEMPLATE,
                        b.getBookingCode() != null ? b.getBookingCode() : b.getId().toString().substring(0, 8),
                        b.getStartAt(),
                        b.getEndAt()
                ))
                .toList();
        List<StationPriceRange> ranges = stationPricingService.resolvePriceRanges(
                stationId,
                decisionAt,
                startAt,
                endAt
        );
        List<PriceSegment> segments = PriceSegmentMapper
                .fromStationPriceRanges(ranges);
        PriceBasis basis = PriceBasis.fixedPackage(connector.getPowerKw());
        PricePreview preview = bookingPriceCalculator.calculate(basis, segments);
        String pricingVersion = PricingVersionHelper.computePricingVersion(
                connector.getId(),
                startAt,
                endAt,
                durationMin,
                CURRENCY,
                preview.totalAmount(),
                preview.pricingBasis(),
                preview.priceLines()
        );
        BookingPolicyResponse policy = bookingPolicyConfig.toPolicyResponse();

        return new PricePreviewResponse(
                pricingVersion,
                connector.getId(),
                startAt,
                endAt,
                durationMin,
                CURRENCY,
                preview.totalAmount(),
                preview.priceLines(),
                preview.pricingBasis(),
                policy,
                overlapWarnings
        );
    }
}
