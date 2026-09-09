package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.response.BookingPolicyResponse;
import com.thang.chargeops.booking.dto.request.PricePreviewRequest;
import com.thang.chargeops.booking.dto.response.PricePreviewResponse;
import com.thang.chargeops.booking.pricing.BookingPriceCalculator;
import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.pricing.PricePreview;
import com.thang.chargeops.booking.pricing.PriceSegment;
import com.thang.chargeops.booking.pricing.PriceSegmentMapper;
import com.thang.chargeops.booking.pricing.PricingVersionHelper;
import com.thang.chargeops.booking.service.BookingPricingService;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.policy.ConnectorBookabilityPolicy;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.service.StationPricingService;
import com.thang.chargeops.station.service.model.StationPriceRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingPricingServiceImpl implements BookingPricingService {

    private static final String CURRENCY = "VND";

    private final ConnectorRepository connectorRepository;
    private final ConnectorBookabilityPolicy connectorBookabilityPolicy;
    private final StationPricingService stationPricingService;
    private final BookingPriceCalculator bookingPriceCalculator;
    private final BookingPolicyConfig bookingPolicyConfig;
    private final Clock applicationClock;

    @Override
    @Transactional(readOnly = true)
    public PricePreviewResponse previewBookingPrice(PricePreviewRequest request) {
        Instant generatedAt = applicationClock.instant();
        Connector connector = requireConnector(request.connectorId());
        connectorBookabilityPolicy.requireBookableForNewBooking(
                connector,
                generatedAt
        );

        UUID stationId = connector.getChargePoint().getStation().getId();
        Instant endAt = request.startAt()
                .plus(Duration.ofMinutes(request.durationMin()));
        List<StationPriceRange> ranges = stationPricingService.resolvePriceRanges(
                stationId,
                generatedAt,
                request.startAt(),
                endAt
        );
        List<PriceSegment> segments = PriceSegmentMapper
                .fromStationPriceRanges(ranges);
        PriceBasis basis = PriceBasis.fixedPackage(connector.getPowerKw());
        PricePreview preview = bookingPriceCalculator.calculate(basis, segments);
        String pricingVersion = PricingVersionHelper.computePricingVersion(
                connector.getId(),
                request.startAt(),
                endAt,
                request.durationMin(),
                CURRENCY,
                preview.totalAmount(),
                preview.pricingBasis(),
                preview.priceLines()
        );
        BookingPolicyResponse policy = bookingPolicyConfig.toPolicyResponse();

        return new PricePreviewResponse(
                pricingVersion,
                connector.getId(),
                request.startAt(),
                endAt,
                request.durationMin(),
                CURRENCY,
                preview.totalAmount(),
                preview.priceLines(),
                preview.pricingBasis(),
                policy,
                List.of() //TODO
        );
    }

    private Connector requireConnector(UUID connectorId) {
        return connectorRepository.findByIdWithChargePointAndStation(connectorId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.CONNECTOR_NOT_FOUND,
                        connectorId
                ));
    }
}
