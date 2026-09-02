package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.mapper.StationAvailabilityMapper;
import com.thang.chargeops.booking.projection.BookingTimeRangeProjection;
import com.thang.chargeops.booking.service.impl.StationAvailabilityServiceImpl;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.exception.AppException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
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
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void assemblesAvailabilityAndClipsAnOverlappingBookingToTheRequestedDay() {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 9, 2);
        Instant dayStart = date.atStartOfDay(SYSTEM_ZONE_ID).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(SYSTEM_ZONE_ID).toInstant();
        Station station = station(stationId);
        Connector connector = connector(station, connectorId);
        StationOperatingSchedule schedule = mock(StationOperatingSchedule.class);
        OperatingWindow operatingWindow = new OperatingWindow(
                localInstant(date, 6, 0),
                localInstant(date, 23, 0)
        );
        BookingTimeRangeProjection overlappingBooking =
                mock(BookingTimeRangeProjection.class);
        when(overlappingBooking.getStartAt()).thenReturn(dayStart.minusSeconds(1800));
        when(overlappingBooking.getEndAt()).thenReturn(dayStart.plusSeconds(3600));
        StationBookingSetting settings = StationBookingSetting.createDefault(station);
        StationPriceRange priceRange = new StationPriceRange(
                dayStart,
                dayEnd,
                new BigDecimal("3400.00"),
                TouRatePeriodCode.NORMAL
        );

        when(connectorRepository.findByIdWithChargePointAndStation(connectorId))
                .thenReturn(Optional.of(connector));
        when(operatingHoursResolver.findActiveSchedule(stationId, NOW))
                .thenReturn(schedule);
        when(operatingHoursResolver.resolveOperatingWindows(schedule, date))
                .thenReturn(List.of(operatingWindow));
        when(bookingRepository.findBlockingRanges(
                eq(connectorId),
                eq(dayStart),
                eq(dayEnd),
                eq(NOW),
                any()
        )).thenReturn(List.of(overlappingBooking));
        when(bookingSettingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(settings));
        when(stationPricingService.resolvePriceRanges(
                stationId,
                NOW,
                dayStart,
                dayEnd
        )).thenReturn(List.of(priceRange));

        StationAvailabilityQuery query = new StationAvailabilityQuery();
        query.setConnectorId(connectorId);
        query.setDate(date);
        StationAvailabilityResponse result = service.getAvailability(stationId, query);

        assertThat(result.stationId()).isEqualTo(stationId);
        assertThat(result.connectorId()).isEqualTo(connectorId);
        assertThat(result.timezone()).isEqualTo(SystemConstant.SYSTEM_REGION_TIMEZONE);
        assertThat(result.generatedAt()).isEqualTo(NOW);
        assertThat(result.operatingWindows()).singleElement().satisfies(window -> {
            assertThat(window.startAt()).isEqualTo(operatingWindow.startAt());
            assertThat(window.endAt()).isEqualTo(operatingWindow.endAt());
        });
        assertThat(result.busyRanges()).singleElement().satisfies(range -> {
            assertThat(range.startAt()).isEqualTo(dayStart);
            assertThat(range.endAt()).isEqualTo(dayStart.plusSeconds(3600));
        });
        assertThat(result.priceRanges()).singleElement()
                .extracting(StationAvailabilityResponse.PriceRangeResponse::rateVndPerKwh)
                .isEqualTo(new BigDecimal("3400.00"));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<BookingStatus>> statuses =
                org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(bookingRepository).findBlockingRanges(
                eq(connectorId),
                eq(dayStart),
                eq(dayEnd),
                eq(NOW),
                statuses.capture()
        );
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                BookingStatus.PENDING,
                BookingStatus.CONFIRMED,
                BookingStatus.CHECKED_IN,
                BookingStatus.CHARGING
        );
        verify(connectorBookabilityPolicy)
                .requireBookableForNewBooking(connector, NOW);
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
}
