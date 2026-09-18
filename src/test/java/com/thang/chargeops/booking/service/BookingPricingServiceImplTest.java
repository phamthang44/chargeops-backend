package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.response.BookingPolicyResponse;
import com.thang.chargeops.booking.dto.request.PricePreviewRequest;
import com.thang.chargeops.booking.dto.response.PricePreviewResponse;
import com.thang.chargeops.booking.pricing.BookingPriceCalculator;
import com.thang.chargeops.booking.policy.BookingTimePolicy;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.impl.BookingPricingServiceImpl;
import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.entity.StationBookingSetting;
import com.thang.chargeops.station.policy.ConnectorBookabilityPolicy;
import com.thang.chargeops.station.policy.StationBusinessEligibilityPolicy;
import com.thang.chargeops.station.policy.impl.ConnectorBookabilityPolicyImpl;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.repository.StationOperatingScheduleRepository;
import com.thang.chargeops.station.repository.StationBookingSettingsRepository;
import com.thang.chargeops.station.service.support.StationOperatingHoursResolver;
import com.thang.chargeops.station.service.StationPricingService;
import com.thang.chargeops.station.service.model.StationPriceRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class BookingPricingServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-09T06:00:00Z");

    @Mock
    private ConnectorRepository connectorRepository;
    @Mock
    private ConnectorBookabilityPolicy connectorBookabilityPolicy;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private StationPricingService stationPricingService;
    @Spy
    private BookingPolicyConfig bookingPolicyConfig = BookingPolicyConfig.defaults();
    @Mock
    private StationOperatingScheduleRepository scheduleRepository;
    @Mock
    private StationBookingSettingsRepository bookingSettingsRepository;
    @Mock
    private CurrentProfileProvider currentProfileProvider;

    private BookingPricingServiceImpl service;
    private UserProfile defaultDriver;

    @BeforeEach
    void setUp() {
        defaultDriver = new UserProfile();
        defaultDriver.setId(UUID.randomUUID());
        defaultDriver.setEmail("driver@chargeops.vn");
        lenient().when(currentProfileProvider.requireProfile()).thenReturn(defaultDriver);
        StationOperatingHoursResolver resolver = new StationOperatingHoursResolver(scheduleRepository);
        service = new BookingPricingServiceImpl(
                connectorRepository,
                connectorBookabilityPolicy,
                bookingRepository,
                stationPricingService,
                new BookingPriceCalculator(),
                bookingPolicyConfig,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new BookingTimePolicy(bookingPolicyConfig, resolver),
                bookingSettingsRepository,
                resolver,
                currentProfileProvider
        );
    }

    @Test
    void buildsStatelessPricePreviewFromStationRangesAndCalculator() {
        UUID stationId = UUID.randomUUID();
        allowSchedule(stationId);
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

    @Test
    void missingConnectorCannotProduceAQuote() {
        UUID connectorId = UUID.randomUUID();
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.previewBookingPrice(
                new PricePreviewRequest(connectorId, NOW.plusSeconds(3600), 60)))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StationErrorCode.CONNECTOR_NOT_FOUND));
        verifyNoInteractions(connectorBookabilityPolicy, stationPricingService, bookingPolicyConfig);
    }

    @Test
    void rejectedBookabilityStopsBeforePricing() {
        UUID connectorId = UUID.randomUUID();
        Connector connector = connector(station(UUID.randomUUID()), connectorId);
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        AppException rejection = new AppException(StationErrorCode.CONNECTOR_NOT_BOOKABLE, "C-01");
        doThrow(rejection).when(connectorBookabilityPolicy)
                .requireBookableForNewBooking(connector, NOW);

        assertThatThrownBy(() -> service.previewBookingPrice(
                new PricePreviewRequest(connectorId, NOW.plusSeconds(3600), 60)))
                .isSameAs(rejection);
        verifyNoInteractions(stationPricingService, bookingPolicyConfig);
    }

    @Test
    void repeatedPreviewUsesCurrentTariffAndChangesConsentVersion() {
        UUID stationId = UUID.randomUUID();
        allowSchedule(stationId);
        UUID connectorId = UUID.randomUUID();
        Instant startAt = NOW.plusSeconds(3600);
        Instant endAt = startAt.plusSeconds(3600);
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector(station(stationId), connectorId)));
        when(bookingPolicyConfig.toPolicyResponse())
                .thenReturn(BookingPolicyConfig.defaults().toPolicyResponse());
        List<StationPriceRange> original = List.of(new StationPriceRange(
                startAt, endAt, BigDecimal.valueOf(3400), TouRatePeriodCode.NORMAL));
        List<StationPriceRange> updated = List.of(new StationPriceRange(
                startAt, endAt, BigDecimal.valueOf(4200), TouRatePeriodCode.NORMAL));
        when(stationPricingService.resolvePriceRanges(stationId, NOW, startAt, endAt))
                .thenReturn(original, original, updated);
        PricePreviewRequest request = new PricePreviewRequest(connectorId, startAt, 60);

        PricePreviewResponse first = service.previewBookingPrice(request);
        PricePreviewResponse unchanged = service.previewBookingPrice(request);
        PricePreviewResponse repriced = service.previewBookingPrice(request);

        assertThat(first.totalAmount()).isEqualTo(126000L);
        assertThat(unchanged.pricingVersion()).isEqualTo(first.pricingVersion());
        assertThat(repriced.totalAmount()).isEqualTo(156000L);
        assertThat(repriced.pricingVersion()).isNotEqualTo(first.pricingVersion());
        assertThat(first.totalAmount()).isEqualTo(126000L);
    }

    @Test
    void rejectsInsufficientLeadBeforeResolvingPrices() {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector(station(stationId), connectorId)));
        assertThatThrownBy(() -> service.previewBookingPrice(
                new PricePreviewRequest(connectorId, NOW.plusSeconds(1800), 60)))
                .isInstanceOfSatisfying(AppException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(BookingErrorCode.TIME_INVALID);
                    assertThat(((java.util.Map<?, ?>) exception.getDetails()).get("reason"))
                            .isEqualTo("MINIMUM_ADVANCE_NOT_MET");
                });
        verifyNoInteractions(stationPricingService);
    }

    @Test
    void missingScheduleCannotProduceAPricePreview() {
        UUID connectorId = UUID.randomUUID();
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector(station(UUID.randomUUID()), connectorId)));
        assertThatThrownBy(() -> service.previewBookingPrice(
                new PricePreviewRequest(connectorId, NOW.plusSeconds(3600), 60)))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(((java.util.Map<?, ?>) exception.getDetails()).get("reason"))
                                .isEqualTo("OPERATING_SCHEDULE_NOT_CONFIGURED"));
        verifyNoInteractions(stationPricingService);
    }

    @Test
    void previewUsesTheStationMinimumDuration() {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector(station(stationId), connectorId)));
        when(bookingSettingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(StationBookingSetting.builder().minDurationMinutes(60).build()));
        assertThatThrownBy(() -> service.previewBookingPrice(
                new PricePreviewRequest(connectorId, NOW.plusSeconds(3600), 30)))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(((java.util.Map<?, ?>) exception.getDetails()).get("reason"))
                                .isEqualTo("DURATION_OUT_OF_RANGE"));
        verifyNoInteractions(stationPricingService);
    }

    @Test
    void overlappingBookingStopsPreviewBeforePricing() {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Instant startAt = NOW.plusSeconds(3600);
        Instant endAt = startAt.plusSeconds(3600);
        allowSchedule(stationId);
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector(station(stationId), connectorId)));
        when(bookingRepository.existsOverlappingBooking(connectorId, startAt, endAt, NOW))
                .thenReturn(true);

        assertThatThrownBy(() -> service.previewBookingPrice(new PricePreviewRequest(connectorId, startAt, 60)))
                .isInstanceOfSatisfying(AppException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(BookingErrorCode.SLOT_UNAVAILABLE);
                    assertThat(exception.getHttpStatus().value()).isEqualTo(409);
                });
        verifyNoInteractions(stationPricingService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inUseConnectorReachesFutureOverlapCheckWithRealBookabilityPolicy(boolean overlaps) {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Instant startAt = NOW.plusSeconds(86400);
        Instant endAt = startAt.plusSeconds(3600);
        Connector connector = connector(station(stationId), connectorId);
        connector.updateRuntimeStatus(RuntimeStatus.IN_USE);
        allowSchedule(stationId);
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(bookingRepository.existsOverlappingBooking(connectorId, startAt, endAt, NOW)).thenReturn(overlaps);
        StationBusinessEligibilityPolicy businessPolicy = mock(StationBusinessEligibilityPolicy.class);
        StationOperatingHoursResolver resolver = new StationOperatingHoursResolver(scheduleRepository);
        BookingPricingServiceImpl realPolicyService = new BookingPricingServiceImpl(
                connectorRepository, new ConnectorBookabilityPolicyImpl(businessPolicy), bookingRepository,
                stationPricingService, new BookingPriceCalculator(), bookingPolicyConfig,
                Clock.fixed(NOW, ZoneOffset.UTC), new BookingTimePolicy(bookingPolicyConfig, resolver),
                bookingSettingsRepository, resolver, currentProfileProvider);
        PricePreviewRequest request = new PricePreviewRequest(connectorId, startAt, 60);

        if (overlaps) {
            assertThatThrownBy(() -> realPolicyService.previewBookingPrice(request))
                    .isInstanceOfSatisfying(AppException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(BookingErrorCode.SLOT_UNAVAILABLE));
            verifyNoInteractions(stationPricingService);
        } else {
            when(stationPricingService.resolvePriceRanges(stationId, NOW, startAt, endAt))
                    .thenReturn(List.of(new StationPriceRange(startAt, endAt,
                            BigDecimal.valueOf(3400), TouRatePeriodCode.NORMAL)));
            assertThat(realPolicyService.previewBookingPrice(request).totalAmount()).isEqualTo(126000L);
        }
        verify(businessPolicy).requireEligibleForNewBusiness(connector.getChargePoint().getStation(), NOW);
        verify(bookingRepository).existsOverlappingBooking(connectorId, startAt, endAt, NOW);
    }

    @Test
    void returnsOverlapWarningWhenDriverHasConflictingBookingOnAnotherConnector() {
        UUID stationId = UUID.randomUUID();
        allowSchedule(stationId);
        UUID connectorId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-09-10T09:30:00Z");
        Instant endAt = Instant.parse("2026-09-10T10:30:00Z");
        Connector connector = connector(station(stationId), connectorId);

        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(stationPricingService.resolvePriceRanges(stationId, NOW, startAt, endAt))
                .thenReturn(List.of(new StationPriceRange(startAt, endAt, BigDecimal.valueOf(3400), TouRatePeriodCode.NORMAL)));

        Booking conflictingBooking = mock(Booking.class);
        when(conflictingBooking.getBookingCode()).thenReturn("BK-CONFLICT-01");
        when(conflictingBooking.getStartAt()).thenReturn(startAt);
        when(conflictingBooking.getEndAt()).thenReturn(endAt);
        when(bookingRepository.findOverlappingDriverBookings(
                eq(defaultDriver.getId()),
                eq(connectorId),
                eq(startAt),
                eq(endAt),
                eq(NOW),
                any()
        )).thenReturn(List.of(conflictingBooking));

        PricePreviewResponse response = service.previewBookingPrice(new PricePreviewRequest(connectorId, startAt, 60));

        assertThat(response.overlapWarnings()).hasSize(1);
        assertThat(response.overlapWarnings().getFirst()).contains("BK-CONFLICT-01");
    }

    @Test
    void returnsEmptyOverlapWarningsWhenDriverHasNoConflictingBookings() {
        UUID stationId = UUID.randomUUID();
        allowSchedule(stationId);
        UUID connectorId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-09-10T09:30:00Z");
        Instant endAt = Instant.parse("2026-09-10T10:30:00Z");
        Connector connector = connector(station(stationId), connectorId);

        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(stationPricingService.resolvePriceRanges(stationId, NOW, startAt, endAt))
                .thenReturn(List.of(new StationPriceRange(startAt, endAt, BigDecimal.valueOf(3400), TouRatePeriodCode.NORMAL)));
        when(bookingRepository.findOverlappingDriverBookings(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        PricePreviewResponse response = service.previewBookingPrice(new PricePreviewRequest(connectorId, startAt, 60));

        assertThat(response.overlapWarnings()).isEmpty();
    }

    @Test
    void warningsDoNotAlterPricingHashOrTotalAmount() {
        UUID stationId = UUID.randomUUID();
        allowSchedule(stationId);
        UUID connectorId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-09-10T09:30:00Z");
        Instant endAt = Instant.parse("2026-09-10T10:30:00Z");
        Connector connector = connector(station(stationId), connectorId);

        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(stationPricingService.resolvePriceRanges(stationId, NOW, startAt, endAt))
                .thenReturn(List.of(new StationPriceRange(startAt, endAt, BigDecimal.valueOf(3400), TouRatePeriodCode.NORMAL)));

        when(bookingRepository.findOverlappingDriverBookings(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        PricePreviewResponse cleanResponse = service.previewBookingPrice(new PricePreviewRequest(connectorId, startAt, 60));

        Booking conflictingBooking = mock(Booking.class);
        when(conflictingBooking.getBookingCode()).thenReturn("BK-CONFLICT-01");
        when(conflictingBooking.getStartAt()).thenReturn(startAt);
        when(conflictingBooking.getEndAt()).thenReturn(endAt);
        when(bookingRepository.findOverlappingDriverBookings(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(conflictingBooking));
        PricePreviewResponse warnedResponse = service.previewBookingPrice(new PricePreviewRequest(connectorId, startAt, 60));

        assertThat(warnedResponse.pricingVersion()).isEqualTo(cleanResponse.pricingVersion());
        assertThat(warnedResponse.totalAmount()).isEqualTo(cleanResponse.totalAmount());
        assertThat(warnedResponse.policy()).isEqualTo(cleanResponse.policy());
        assertThat(warnedResponse.overlapWarnings()).hasSize(1);
        assertThat(cleanResponse.overlapWarnings()).isEmpty();
    }

    @Test
    void repriceUnderLock_whenTariffChangesToPeak_returnsNewPriceLinesAndDifferentVersion() {
        UUID stationId = UUID.randomUUID();
        allowSchedule(stationId);
        UUID connectorId = UUID.randomUUID();
        Instant startAt = NOW.plusSeconds(3600);
        Instant endAt = startAt.plusSeconds(3600);
        Connector connector = connector(station(stationId), connectorId);

        when(bookingPolicyConfig.toPolicyResponse())
                .thenReturn(BookingPolicyConfig.defaults().toPolicyResponse());

        when(stationPricingService.resolvePriceRanges(stationId, NOW, startAt, endAt))
                .thenReturn(List.of(new StationPriceRange(startAt, endAt, BigDecimal.valueOf(3400), TouRatePeriodCode.NORMAL)))
                .thenReturn(List.of(new StationPriceRange(startAt, endAt, BigDecimal.valueOf(4500), TouRatePeriodCode.PEAK)));

        PricePreviewResponse normal = service.repriceUnderLock(defaultDriver, connector, startAt, 60, NOW);
        PricePreviewResponse peak = service.repriceUnderLock(defaultDriver, connector, startAt, 60, NOW);

        assertThat(normal.totalAmount()).isEqualTo(126000L);
        assertThat(peak.totalAmount()).isEqualTo(167000L);
        assertThat(peak.pricingVersion()).isNotEqualTo(normal.pricingVersion());
        assertThat(peak.priceLines().get(0).periodCode()).isEqualTo(TouRatePeriodCode.PEAK);
    }

    @Test
    void previewBookingPrice_crossMidnightAcrossDayBoundary_splitsPriceLinesAccurately() {
        UUID stationId = UUID.randomUUID();
        allowSchedule(stationId);
        UUID connectorId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-09-10T16:30:00Z"); // 23:30 VN
        Instant midnight = Instant.parse("2026-09-10T17:00:00Z"); // 00:00 VN
        Instant endAt = Instant.parse("2026-09-10T18:00:00Z"); // 01:00 VN next day
        Connector connector = connector(station(stationId), connectorId);

        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(bookingPolicyConfig.toPolicyResponse())
                .thenReturn(BookingPolicyConfig.defaults().toPolicyResponse());

        when(stationPricingService.resolvePriceRanges(stationId, NOW, startAt, endAt))
                .thenReturn(List.of(
                        new StationPriceRange(startAt, midnight, BigDecimal.valueOf(3400), TouRatePeriodCode.NORMAL),
                        new StationPriceRange(midnight, endAt, BigDecimal.valueOf(2500), TouRatePeriodCode.OFF_PEAK)
                ));

        PricePreviewResponse response = service.previewBookingPrice(
                new PricePreviewRequest(connectorId, startAt, 90)
        );

        assertThat(response.priceLines()).hasSize(2);
        assertThat(response.priceLines().get(0).durationMin()).isEqualTo(30);
        assertThat(response.priceLines().get(0).periodCode()).isEqualTo(TouRatePeriodCode.NORMAL);
        assertThat(response.priceLines().get(1).durationMin()).isEqualTo(60);
        assertThat(response.priceLines().get(1).periodCode()).isEqualTo(TouRatePeriodCode.OFF_PEAK);
        long expectedTotal = response.priceLines().get(0).amount() + response.priceLines().get(1).amount();
        assertThat(response.totalAmount()).isEqualTo(expectedTotal);
        assertThat(response.pricingVersion()).hasSize(64);
    }

    private void allowSchedule(UUID stationId) {
        when(scheduleRepository.findActiveByStationId(stationId, NOW)).thenReturn(Optional.of(
                StationOperatingSchedule.builder().open24Hours(true)
                        .effectiveFrom(NOW.minusSeconds(86400)).build()));
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
