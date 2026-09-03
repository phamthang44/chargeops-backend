package com.thang.chargeops.station.service;

import com.thang.chargeops.booking.policy.BookingCancellationPolicy;
import com.thang.chargeops.booking.policy.model.CancellationPolicySummary;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryDetailResponse;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.mapper.StationDetailMapper;
import com.thang.chargeops.station.policy.StationVisibilityPolicy;
import com.thang.chargeops.station.repository.ChargePointRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.service.impl.StationDetailServiceImpl;
import com.thang.chargeops.station.service.support.StationOperatingHoursResolver;
import com.thang.chargeops.station.service.support.StationOperatingStatus;
import com.thang.chargeops.station.service.support.StationEffectiveStateResolver;
import com.thang.chargeops.common.enums.StationOperationalStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationDetailServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-01T05:00:00Z");

    @Mock
    private StationRepository stationRepository;

    @Mock
    private ChargePointRepository chargePointRepository;

    @Mock
    private StationVisibilityPolicy stationVisibilityPolicy;

    @Mock
    private StationOperatingHoursResolver operatingHoursResolver;

    @Mock
    private StationEffectiveStateResolver effectiveStateResolver;

    @Mock
    private StationPricingService stationPricingService;

    @Mock
    private StationDetailMapper stationDetailMapper;

    @Mock
    private BookingCancellationPolicy bookingCancellationPolicy;

    private StationDetailServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StationDetailServiceImpl(
                stationRepository,
                chargePointRepository,
                stationVisibilityPolicy,
                operatingHoursResolver,
                effectiveStateResolver,
                stationPricingService,
                stationDetailMapper,
                bookingCancellationPolicy,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void assemblesDetailUsingOneConsistentRequestTime() {
        UUID stationId = UUID.randomUUID();
        Station station = mock(Station.class);
        StationOperatingSchedule schedule = mock(StationOperatingSchedule.class);
        List<ChargePoint> chargePoints = List.of(mock(ChargePoint.class));
        BigDecimal price = new BigDecimal("3500.00");
        StationDiscoveryDetailResponse expected =
                mock(StationDiscoveryDetailResponse.class);
        CancellationPolicySummary policySummary =
                mock(CancellationPolicySummary.class);

        when(stationRepository.findDiscoveryDetailById(stationId))
                .thenReturn(Optional.of(station));
        when(stationVisibilityPolicy.isVisibleToDrivers(station, NOW))
                .thenReturn(true);
        when(station.getOperationalStatus()).thenReturn(StationOperationalStatus.OPERATING);
        when(operatingHoursResolver.findActiveSchedule(stationId, NOW))
                .thenReturn(schedule);
        when(chargePointRepository.findDiscoveryEquipment(stationId))
                .thenReturn(chargePoints);
        when(stationPricingService.resolvePriceAt(stationId, NOW))
                .thenReturn(price);
        StationOperatingStatus operatingStatus = StationOperatingStatus.open();
        when(effectiveStateResolver.resolve(
                true,
                StationOperationalStatus.OPERATING,
                schedule,
                NOW
        ))
                .thenReturn(operatingStatus);
        when(bookingCancellationPolicy.getSummary()).thenReturn(policySummary);
        when(stationDetailMapper.toResponse(
                station,
                schedule,
                chargePoints,
                price,
                operatingStatus,
                policySummary
        )).thenReturn(expected);

        StationDiscoveryDetailResponse result = service.getStationDetail(stationId);

        assertThat(result).isSameAs(expected);
        verify(operatingHoursResolver).findActiveSchedule(stationId, NOW);
        verify(stationPricingService).resolvePriceAt(stationId, NOW);
        verify(effectiveStateResolver).resolve(
                true,
                StationOperationalStatus.OPERATING,
                schedule,
                NOW
        );
    }

    @Test
    void hidesStationThatIsNotEligibleForDriverBusiness() {
        UUID stationId = UUID.randomUUID();
        Station station = mock(Station.class);
        when(stationRepository.findDiscoveryDetailById(stationId))
                .thenReturn(Optional.of(station));
        when(stationVisibilityPolicy.isVisibleToDrivers(station, NOW))
                .thenReturn(false);

        assertThatThrownBy(() -> service.getStationDetail(stationId))
                .isInstanceOf(AppException.class);

        verifyNoInteractions(
                chargePointRepository,
                stationPricingService,
                stationDetailMapper,
                bookingCancellationPolicy
        );
        verify(operatingHoursResolver, never()).findActiveSchedule(stationId, NOW);
    }

    @Test
    void returnsPausedStationDetailWhenItRemainsVisibleToDrivers() {
        UUID stationId = UUID.randomUUID();
        Station station = mock(Station.class);
        StationOperatingSchedule schedule = mock(StationOperatingSchedule.class);
        StationOperatingStatus pausedStatus =
                StationOperatingStatus.pausedByOwner(true);
        StationDiscoveryDetailResponse expected =
                mock(StationDiscoveryDetailResponse.class);
        CancellationPolicySummary policySummary =
                mock(CancellationPolicySummary.class);

        when(stationRepository.findDiscoveryDetailById(stationId))
                .thenReturn(Optional.of(station));
        when(stationVisibilityPolicy.isVisibleToDrivers(station, NOW))
                .thenReturn(true);
        when(station.getOperationalStatus()).thenReturn(StationOperationalStatus.PAUSED);
        when(operatingHoursResolver.findActiveSchedule(stationId, NOW))
                .thenReturn(schedule);
        when(chargePointRepository.findDiscoveryEquipment(stationId))
                .thenReturn(List.of());
        when(stationPricingService.resolvePriceAt(stationId, NOW))
                .thenReturn(BigDecimal.ZERO);
        when(effectiveStateResolver.resolve(
                true,
                StationOperationalStatus.PAUSED,
                schedule,
                NOW
        )).thenReturn(pausedStatus);
        when(bookingCancellationPolicy.getSummary()).thenReturn(policySummary);
        when(stationDetailMapper.toResponse(
                station,
                schedule,
                List.of(),
                BigDecimal.ZERO,
                pausedStatus,
                policySummary
        )).thenReturn(expected);

        assertThat(service.getStationDetail(stationId)).isSameAs(expected);
        verify(effectiveStateResolver).resolve(
                true,
                StationOperationalStatus.PAUSED,
                schedule,
                NOW
        );
    }

    @Test
    void returnsNotFoundBeforeCallingDownstreamServicesWhenStationDoesNotExist() {
        UUID stationId = UUID.randomUUID();
        when(stationRepository.findDiscoveryDetailById(stationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStationDetail(stationId))
                .isInstanceOf(AppException.class);

        verifyNoInteractions(
                stationVisibilityPolicy,
                chargePointRepository,
                operatingHoursResolver,
                stationPricingService,
                stationDetailMapper,
                bookingCancellationPolicy
        );
    }
}
