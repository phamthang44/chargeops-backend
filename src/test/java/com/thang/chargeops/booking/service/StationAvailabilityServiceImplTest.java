package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.response.BookingPolicyResponse;
import com.thang.chargeops.booking.mapper.StationAvailabilityMapper;
import com.thang.chargeops.booking.policy.BookingTimePolicy;
import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.projection.BookingTimeRangeProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.impl.StationAvailabilityServiceImpl;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.station.dto.station.filter.StationAvailabilityQuery;
import com.thang.chargeops.station.dto.station.response.StationAvailabilityResponse;
import com.thang.chargeops.station.entity.ChargePoint;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationAvailabilityServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-01T05:00:00Z");
    private static final ZoneId SYSTEM_ZONE_ID =
            ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);

    @Mock
    private ConnectorRepository connectorRepository;
    @Mock
    private ConnectorBookabilityPolicy connectorBookabilityPolicy;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private StationBookingSettingsRepository bookingSettingsRepository;
    @Mock
    private StationOperatingHoursResolver operatingHoursResolver;
    @Mock
    private StationPricingService stationPricingService;
    @Mock
    private BookingTimePolicy bookingTimePolicy;
    @Mock
    private BookingPolicyConfig bookingPolicyConfig;

    private StationAvailabilityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StationAvailabilityServiceImpl(
                connectorRepository,
                connectorBookabilityPolicy,
                bookingRepository,
                bookingSettingsRepository,
                operatingHoursResolver,
                stationPricingService,
                new StationAvailabilityMapper(),
                bookingTimePolicy,
                bookingPolicyConfig,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void assemblesAvailabilityWithFullCoverageAcrossMidnightAndPreservesAllContractFields() {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 9, 2);
        Instant dayStart = date.atStartOfDay(SYSTEM_ZONE_ID).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(SYSTEM_ZONE_ID).toInstant();
        Instant coverageStartAt = dayStart;
        Instant coverageEndAt = dayEnd.plus(Duration.ofMinutes(180));
        Instant earliestStartAt = Instant.parse("2026-09-01T06:00:00Z");

        Station station = station(stationId);
        Connector connector = connector(station, connectorId);
        StationOperatingSchedule schedule = mock(StationOperatingSchedule.class);
        OperatingWindow operatingWindow = new OperatingWindow(
                localInstant(date, 6, 0),
                coverageEndAt
        );

        BookingTimeRangeProjection overlappingBooking = mock(BookingTimeRangeProjection.class);
        when(overlappingBooking.getStartAt()).thenReturn(dayStart.minusSeconds(1800));
        when(overlappingBooking.getEndAt()).thenReturn(dayStart.plusSeconds(3600));

        StationBookingSetting settings = StationBookingSetting.createDefault(station);
        StationPriceRange priceRange = new StationPriceRange(
                coverageStartAt,
                coverageEndAt,
                new BigDecimal("3400.00"),
                TouRatePeriodCode.NORMAL
        );

        when(bookingPolicyConfig.getMaxDurationMinutes()).thenReturn(180);
        when(bookingPolicyConfig.getDurationStepMinutes()).thenReturn(30);
        when(bookingPolicyConfig.getMinDurationMinutes()).thenReturn(30);
        when(bookingPolicyConfig.toPolicyResponse()).thenReturn(policyResponse("booking-v4.9"));
        when(bookingTimePolicy.earliestStartAt(NOW)).thenReturn(earliestStartAt);

        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(operatingHoursResolver.findActiveSchedule(stationId, NOW))
                .thenReturn(schedule);
        when(operatingHoursResolver.resolveOperatingWindows(schedule, date, coverageStartAt, coverageEndAt))
                .thenReturn(List.of(operatingWindow));
        when(bookingRepository.findBlockingRanges(
                eq(connectorId),
                eq(coverageStartAt),
                eq(coverageEndAt),
                eq(NOW),
                any()
        )).thenReturn(List.of(overlappingBooking));
        when(bookingSettingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(settings));
        when(stationPricingService.resolvePriceRanges(
                stationId,
                NOW,
                coverageStartAt,
                coverageEndAt
        )).thenReturn(List.of(priceRange));

        StationAvailabilityQuery query = new StationAvailabilityQuery();
        query.setConnectorId(connectorId);
        query.setDate(date);
        StationAvailabilityResponse result = service.getAvailability(stationId, query);

        assertThat(result.stationId()).isEqualTo(stationId);
        assertThat(result.connectorId()).isEqualTo(connectorId);
        assertThat(result.date()).isEqualTo(date);
        assertThat(result.timezone()).isEqualTo(SystemConstant.SYSTEM_REGION_TIMEZONE);
        assertThat(result.generatedAt()).isEqualTo(NOW);
        assertThat(result.earliestStartAt()).isEqualTo(earliestStartAt);
        assertThat(result.coverageStartAt()).isEqualTo(coverageStartAt);
        assertThat(result.coverageEndAt()).isEqualTo(coverageEndAt);
        assertThat(result.minDurationMinutes()).isEqualTo(30);
        assertThat(result.durationStepMinutes()).isEqualTo(30);
        assertThat(result.maxDurationMinutes()).isEqualTo(180);
        assertThat(result.policyVersion()).isEqualTo("booking-v4.9");
        assertThat(result.pricingEstimateParameters()).isNotNull();
        assertThat(result.pricingEstimateParameters().powerKw()).isEqualByComparingTo("120.00");
        assertThat(result.pricingEstimateParameters().energyFactor()).isEqualTo(PriceBasis.ENERGY_FACTOR);
        assertThat(result.pricingEstimateParameters().formulaVersion()).isEqualTo("booking-estimate-v1");

        assertThat(result.operatingWindows()).singleElement().satisfies(window -> {
            assertThat(window.startAt()).isEqualTo(operatingWindow.startAt());
            assertThat(window.endAt()).isEqualTo(operatingWindow.endAt());
        });
        assertThat(result.busyRanges()).singleElement().satisfies(range -> {
            assertThat(range.startAt()).isEqualTo(coverageStartAt);
            assertThat(range.endAt()).isEqualTo(dayStart.plusSeconds(3600));
        });
        assertThat(result.priceRanges()).singleElement()
                .extracting(StationAvailabilityResponse.PriceRangeResponse::rateVndPerKwh)
                .isEqualTo(new BigDecimal("3400.00"));

        verify(bookingTimePolicy).validateBookingDate(date, NOW);
        verify(connectorBookabilityPolicy).requireBookableForNewBooking(connector, NOW);
    }

    @Test
    void earliestStartAtRespectsLeadTimeAndGridAcrossMidnight() {
        // 23:40 tại VN (UTC+7) = 16:40 UTC
        Instant nowLate = Instant.parse("2026-09-01T16:40:00Z");
        Instant expectedEarliest = Instant.parse("2026-09-01T18:00:00Z"); // 01:00 ngày 2026-09-02 tại VN

        StationAvailabilityServiceImpl lateService = new StationAvailabilityServiceImpl(
                connectorRepository,
                connectorBookabilityPolicy,
                bookingRepository,
                bookingSettingsRepository,
                operatingHoursResolver,
                stationPricingService,
                new StationAvailabilityMapper(),
                bookingTimePolicy,
                bookingPolicyConfig,
                Clock.fixed(nowLate, ZoneOffset.UTC)
        );

        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 9, 2);
        Station station = station(stationId);
        Connector connector = connector(station, connectorId);
        StationBookingSetting settings = StationBookingSetting.createDefault(station);

        when(bookingTimePolicy.earliestStartAt(nowLate)).thenReturn(expectedEarliest);
        when(bookingPolicyConfig.getMaxDurationMinutes()).thenReturn(180);
        when(bookingPolicyConfig.getDurationStepMinutes()).thenReturn(30);
        when(bookingPolicyConfig.getMinDurationMinutes()).thenReturn(30);
        when(bookingPolicyConfig.toPolicyResponse()).thenReturn(policyResponse("booking-v4.9"));
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(operatingHoursResolver.findActiveSchedule(eq(stationId), eq(nowLate)))
                .thenReturn(mock(StationOperatingSchedule.class));
        when(bookingSettingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(settings));

        StationAvailabilityQuery query = new StationAvailabilityQuery();
        query.setConnectorId(connectorId);
        query.setDate(date);

        StationAvailabilityResponse response = lateService.getAvailability(stationId, query);
        assertThat(response.earliestStartAt()).isEqualTo(expectedEarliest);
    }

    @Test
    void todayHasNoValidStartWhenEarliestStartCrossesMidnight() {
        // 23:40 tại VN (UTC+7) ngày 2026-09-01
        Instant nowLate = Instant.parse("2026-09-01T16:40:00Z");
        Instant expectedEarliest = Instant.parse("2026-09-01T18:00:00Z"); // 01:00 ngày 2026-09-02 (vượt qua 24:00 hôm nay)

        StationAvailabilityServiceImpl lateService = new StationAvailabilityServiceImpl(
                connectorRepository,
                connectorBookabilityPolicy,
                bookingRepository,
                bookingSettingsRepository,
                operatingHoursResolver,
                stationPricingService,
                new StationAvailabilityMapper(),
                bookingTimePolicy,
                bookingPolicyConfig,
                Clock.fixed(nowLate, ZoneOffset.UTC)
        );

        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 9, 1);
        Instant todayDayEnd = today.plusDays(1).atStartOfDay(SYSTEM_ZONE_ID).toInstant(); // 2026-09-01T17:00:00Z

        Station station = station(stationId);
        Connector connector = connector(station, connectorId);
        StationBookingSetting settings = StationBookingSetting.createDefault(station);

        when(bookingTimePolicy.earliestStartAt(nowLate)).thenReturn(expectedEarliest);
        when(bookingPolicyConfig.getMaxDurationMinutes()).thenReturn(180);
        when(bookingPolicyConfig.getDurationStepMinutes()).thenReturn(30);
        when(bookingPolicyConfig.getMinDurationMinutes()).thenReturn(30);
        when(bookingPolicyConfig.toPolicyResponse()).thenReturn(policyResponse("booking-v4.9"));
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(operatingHoursResolver.findActiveSchedule(eq(stationId), eq(nowLate)))
                .thenReturn(mock(StationOperatingSchedule.class));
        when(bookingSettingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(settings));

        StationAvailabilityQuery query = new StationAvailabilityQuery();
        query.setConnectorId(connectorId);
        query.setDate(today);

        StationAvailabilityResponse response = lateService.getAvailability(stationId, query);
        assertThat(response.earliestStartAt()).isAfter(todayDayEnd);
    }

    @Test
    void tomorrowExtendsCoverageToFollowingDay() {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        LocalDate tomorrow = LocalDate.of(2026, 9, 2);
        Instant dayEnd = tomorrow.plusDays(1).atStartOfDay(SYSTEM_ZONE_ID).toInstant(); // 2026-09-03 00:00
        Instant expectedCoverageEnd = dayEnd.plus(Duration.ofMinutes(180)); // 2026-09-03 03:00

        Station station = station(stationId);
        Connector connector = connector(station, connectorId);
        StationBookingSetting settings = StationBookingSetting.createDefault(station);

        when(bookingTimePolicy.earliestStartAt(NOW)).thenReturn(Instant.parse("2026-09-01T06:00:00Z"));
        when(bookingPolicyConfig.getMaxDurationMinutes()).thenReturn(180);
        when(bookingPolicyConfig.getDurationStepMinutes()).thenReturn(30);
        when(bookingPolicyConfig.getMinDurationMinutes()).thenReturn(30);
        when(bookingPolicyConfig.toPolicyResponse()).thenReturn(policyResponse("booking-v4.9"));
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(operatingHoursResolver.findActiveSchedule(eq(stationId), eq(NOW)))
                .thenReturn(mock(StationOperatingSchedule.class));
        when(bookingSettingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(settings));

        StationAvailabilityQuery query = new StationAvailabilityQuery();
        query.setConnectorId(connectorId);
        query.setDate(tomorrow);

        StationAvailabilityResponse response = service.getAvailability(stationId, query);
        assertThat(response.coverageEndAt()).isEqualTo(expectedCoverageEnd);
        // Xác nhận coverageEndAt thuộc ngày thứ 3 (2026-09-03)
        assertThat(response.coverageEndAt().atZone(SYSTEM_ZONE_ID).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    void preservesClosedHoursAfterMidnight() {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 9, 2);
        Instant dayStart = date.atStartOfDay(SYSTEM_ZONE_ID).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(SYSTEM_ZONE_ID).toInstant();
        Instant coverageStartAt = dayStart;
        Instant coverageEndAt = dayEnd.plus(Duration.ofMinutes(180));

        Station station = station(stationId);
        Connector connector = connector(station, connectorId);
        StationOperatingSchedule schedule = mock(StationOperatingSchedule.class);
        // Trạm đóng cửa lúc 22:00, không mở sau nửa đêm
        OperatingWindow windowUntil22 = new OperatingWindow(
                localInstant(date, 8, 0),
                localInstant(date, 22, 0)
        );

        when(bookingTimePolicy.earliestStartAt(NOW)).thenReturn(Instant.parse("2026-09-01T06:00:00Z"));
        when(bookingPolicyConfig.getMaxDurationMinutes()).thenReturn(180);
        when(bookingPolicyConfig.getDurationStepMinutes()).thenReturn(30);
        when(bookingPolicyConfig.getMinDurationMinutes()).thenReturn(30);
        when(bookingPolicyConfig.toPolicyResponse()).thenReturn(policyResponse("booking-v4.9"));
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(operatingHoursResolver.findActiveSchedule(eq(stationId), eq(NOW)))
                .thenReturn(schedule);
        when(operatingHoursResolver.resolveOperatingWindows(schedule, date, coverageStartAt, coverageEndAt))
                .thenReturn(List.of(windowUntil22));
        when(bookingSettingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(StationBookingSetting.createDefault(station)));

        StationAvailabilityQuery query = new StationAvailabilityQuery();
        query.setConnectorId(connectorId);
        query.setDate(date);

        StationAvailabilityResponse response = service.getAvailability(stationId, query);
        assertThat(response.operatingWindows()).singleElement().satisfies(window -> {
            assertThat(window.startAt()).isEqualTo(windowUntil22.startAt());
            assertThat(window.endAt()).isEqualTo(windowUntil22.endAt());
        });
        // Khẳng định không có window nào vượt quá 22:00
        assertThat(response.operatingWindows().getFirst().endAt()).isEqualTo(localInstant(date, 22, 0));
    }

    @Test
    void excludesExpiredPendingHoldsFromBusyRanges() {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 9, 2);
        Station station = station(stationId);
        Connector connector = connector(station, connectorId);

        when(bookingPolicyConfig.getMaxDurationMinutes()).thenReturn(180);
        when(bookingPolicyConfig.getDurationStepMinutes()).thenReturn(30);
        when(bookingPolicyConfig.getMinDurationMinutes()).thenReturn(30);
        when(bookingPolicyConfig.toPolicyResponse()).thenReturn(policyResponse("booking-v4.9"));
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(operatingHoursResolver.findActiveSchedule(eq(stationId), eq(NOW)))
                .thenReturn(mock(StationOperatingSchedule.class));
        when(bookingSettingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(StationBookingSetting.createDefault(station)));

        StationAvailabilityQuery query = new StationAvailabilityQuery();
        query.setConnectorId(connectorId);
        query.setDate(date);

        service.getAvailability(stationId, query);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<BookingStatus>> statusesCaptor =
                ArgumentCaptor.forClass(Collection.class);
        verify(bookingRepository).findBlockingRanges(
                eq(connectorId),
                any(),
                any(),
                eq(NOW),
                statusesCaptor.capture()
        );
        assertThat(statusesCaptor.getValue()).containsExactlyInAnyOrder(
                BookingStatus.PENDING,
                BookingStatus.CONFIRMED,
                BookingStatus.CHECKED_IN,
                BookingStatus.CHARGING
        );
    }

    @Test
    void rejectsDateOutsideTodayAndTomorrow() {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        LocalDate invalidDate = LocalDate.of(2026, 8, 31); // quá khứ

        doThrow(new AppException(BookingErrorCode.TIME_INVALID))
                .when(bookingTimePolicy).validateBookingDate(invalidDate, NOW);

        StationAvailabilityQuery query = new StationAvailabilityQuery();
        query.setConnectorId(connectorId);
        query.setDate(invalidDate);

        assertThatThrownBy(() -> service.getAvailability(stationId, query))
                .isInstanceOfSatisfying(
                        AppException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(BookingErrorCode.TIME_INVALID)
                );

        verifyNoInteractions(
                connectorRepository,
                connectorBookabilityPolicy,
                bookingRepository,
                bookingSettingsRepository,
                operatingHoursResolver,
                stationPricingService
        );
    }

    @Test
    void rejectsConnectorThatBelongsToAnotherStation() {
        UUID requestedStationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        Connector connector = connector(station(UUID.randomUUID()), connectorId);
        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        StationAvailabilityQuery query = new StationAvailabilityQuery();
        query.setConnectorId(connectorId);
        query.setDate(LocalDate.of(2026, 9, 2));

        assertThatThrownBy(() -> service.getAvailability(requestedStationId, query))
                .isInstanceOf(AppException.class);

        verifyNoInteractions(
                connectorBookabilityPolicy,
                bookingRepository,
                bookingSettingsRepository,
                operatingHoursResolver,
                stationPricingService
        );
    }

    @Test
    void throwsConflictWhenPricingNotConfigured() {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 9, 2);
        Station station = station(stationId);
        Connector connector = connector(station, connectorId);
        StationOperatingSchedule schedule = mock(StationOperatingSchedule.class);

        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(operatingHoursResolver.findActiveSchedule(stationId, NOW))
                .thenReturn(schedule);
        when(bookingSettingsRepository.findByStationId(stationId))
                .thenReturn(Optional.empty());

        StationAvailabilityQuery query = new StationAvailabilityQuery();
        query.setConnectorId(connectorId);
        query.setDate(date);

        assertThatThrownBy(() -> service.getAvailability(stationId, query))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(BookingErrorCode.PRICING_NOT_CONFIGURED)
                );
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
                "Trụ 1",
                "Khu A",
                new BigDecimal("120.00")
        );
        chargePoint.activate();
        Connector connector = Connector.create(
                chargePoint,
                "C-01",
                ConnectorType.CCS2,
                new BigDecimal("120.00"),
                ChargerType.DC
        );
        connector.setId(connectorId);
        chargePoint.addConnector(connector);
        return connector;
    }

    private Instant localInstant(LocalDate date, int hour, int minute) {
        return date.atTime(LocalTime.of(hour, minute))
                .atZone(SYSTEM_ZONE_ID)
                .toInstant();
    }

    private BookingPolicyResponse policyResponse(String policyVersion) {
        return new BookingPolicyResponse(
                policyVersion,
                SystemConstant.SYSTEM_REGION_TIMEZONE,
                60,
                List.of(0, 1),
                30,
                30,
                30,
                10,
                10,
                15,
                0,
                100,
                0,
                0,
                List.of("SIMULATOR"),
                "Summary"
        );
    }
}
