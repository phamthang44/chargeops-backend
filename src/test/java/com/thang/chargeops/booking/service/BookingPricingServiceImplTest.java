package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.response.BookingPolicyResponse;
import com.thang.chargeops.booking.dto.request.PricePreviewRequest;
import com.thang.chargeops.booking.dto.response.PricePreviewResponse;
import com.thang.chargeops.booking.pricing.BookingPriceCalculator;
import com.thang.chargeops.booking.service.impl.BookingPricingServiceImpl;
import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.ConnectorBookabilityPolicy;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.service.StationPricingService;
import com.thang.chargeops.station.service.model.StationPriceRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingPricingServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-09T06:00:00Z");

    @Mock
    private ConnectorRepository connectorRepository;
    @Mock
    private ConnectorBookabilityPolicy connectorBookabilityPolicy;
    @Mock
    private StationPricingService stationPricingService;
    @Mock
    private BookingPolicyConfig bookingPolicyConfig;

    private BookingPricingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BookingPricingServiceImpl(
                connectorRepository,
                connectorBookabilityPolicy,
                stationPricingService,
                new BookingPriceCalculator(),
                bookingPolicyConfig,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void buildsStatelessPricePreviewFromStationRangesAndCalculator() {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-09-10T09:30:00Z");
        Instant splitAt = Instant.parse("2026-09-10T10:00:00Z");
        Instant endAt = Instant.parse("2026-09-10T10:30:00Z");
        Connector connector = connector(station(stationId), connectorId);
        BookingPolicyResponse policy = BookingPolicyConfig.defaults()
                .toPolicyResponse();

        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(stationPricingService.resolvePriceRanges(
                stationId,
                NOW,
                startAt,
                endAt
        )).thenReturn(List.of(
                new StationPriceRange(
                        startAt,
                        splitAt,
                        BigDecimal.valueOf(3400),
                        TouRatePeriodCode.NORMAL
                ),
                new StationPriceRange(
                        splitAt,
                        endAt,
                        BigDecimal.valueOf(4200),
                        TouRatePeriodCode.PEAK
                )
        ));
        when(bookingPolicyConfig.toPolicyResponse()).thenReturn(policy);

        PricePreviewResponse response = service.previewBookingPrice(
                new PricePreviewRequest(connectorId, startAt, 60)
        );

        assertThat(response.connectorId()).isEqualTo(connectorId);
        assertThat(response.startAt()).isEqualTo(startAt);
        assertThat(response.endAt()).isEqualTo(endAt);
        assertThat(response.totalAmount()).isEqualTo(141000L);
        assertThat(response.pricingVersion()).hasSize(64);
        assertThat(response.priceLines()).hasSize(2);
        assertThat(response.priceLines())
                .extracting(line -> line.estimatedEnergyKwh().toPlainString())
                .containsExactly("18.6", "18.6");
        assertThat(response.priceLines())
                .extracting("amount")
                .containsExactly(63000L, 78000L);
        assertThat(response.pricingBasis().powerKw())
                .isEqualByComparingTo("60.00");
        assertThat(response.policy()).isEqualTo(policy);
        assertThat(response.overlapWarnings()).isEmpty();
        verify(connectorBookabilityPolicy)
                .requireBookableForNewBooking(connector, NOW);
    }

    private Station station(UUID stationId) {
        Station station = new Station();
        station.setId(stationId);
        return station;
    }

    private Connector connector(Station station, UUID connectorId) {
        ChargePoint chargePoint = ChargePoint.create(
                station,
                "CP-01",
                "Tru 1",
                "Khu A",
                new BigDecimal("120.00")
        );
        chargePoint.activate();
        Connector connector = Connector.create(
                chargePoint,
                "C-01",
                ConnectorType.CCS2,
                new BigDecimal("60.00"),
                ChargerType.DC
        );
        connector.setId(connectorId);
        return connector;
    }
}
