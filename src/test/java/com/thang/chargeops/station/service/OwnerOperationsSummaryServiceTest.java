package com.thang.chargeops.station.service;

import com.thang.chargeops.booking.repository.OwnerBookingReadRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.repository.ChargePointRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.service.usecase.OwnerStationUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OwnerOperationsSummaryServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-05T18:00:00Z");
    private static final Instant DAY_START = Instant.parse("2026-10-05T17:00:00Z");
    private final UUID ownerId = UUID.randomUUID();
    private final StationRepository stations = mock(StationRepository.class);
    private final ChargePointRepository points = mock(ChargePointRepository.class);
    private final ConnectorRepository connectors = mock(ConnectorRepository.class);
    private final OwnerBookingReadRepository bookings = mock(OwnerBookingReadRepository.class);
    private final Clock clock = mock(Clock.class);
    private final CurrentProfileProvider profiles = mock(CurrentProfileProvider.class);
    private OwnerStationUseCase useCase;

    @BeforeEach
    void setUp() {
        var owner = mock(UserProfile.class);
        when(profiles.requireProfile()).thenReturn(owner);
        when(owner.getId()).thenReturn(ownerId);
        when(clock.instant()).thenReturn(NOW);
        when(stations.summarizeOwnerOperations(ownerId, NOW))
                .thenReturn(mock(StationRepository.OwnerOperationsTotals.class));
        when(points.summarizeOwnerOperations(ownerId))
                .thenReturn(mock(ChargePointRepository.OwnerOperationsTotals.class));
        when(connectors.summarizeOwnerOperations(ownerId))
                .thenReturn(mock(ConnectorRepository.OwnerOperationsTotals.class));
        when(bookings.findOwnerChargingWindows(ownerId, DAY_START, NOW)).thenReturn(List.of());
        useCase = new OwnerStationUseCase(stations, null, profiles, null, null, null, null,
                points, connectors, bookings, clock);
    }

    @Test
    void usesOwnerScopeAndVietnamDayAndIncludesOvernightUtilization() {
        var stationCounts = stations.summarizeOwnerOperations(ownerId, NOW);
        when(stationCounts.getVisibleToDrivers()).thenReturn(5L);
        when(connectors.summarizeOwnerOperations(ownerId).getTotalConnectors()).thenReturn(2L);
        var windows = List.of(
                window(DAY_START.minusSeconds(3600), DAY_START.plusSeconds(1800), null, NOW),
                window(DAY_START.plusSeconds(1800), null, null, NOW.plusSeconds(3600)));
        when(bookings.findOwnerChargingWindows(ownerId, DAY_START, NOW)).thenReturn(windows);
        var result = useCase.getOperationsSummary();
        assertThat(result.date()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(result.hardware().sessionsToday()).isEqualTo(1);
        assertThat(result.hardware().averageUtilizationPercent()).isEqualByComparingTo("50.00");
        assertThat(result.stations().visibleToDrivers()).isEqualTo(5);
        verify(bookings, atLeastOnce()).findOwnerChargingWindows(ownerId, DAY_START, NOW);
        verify(stations, atLeastOnce()).summarizeOwnerOperations(ownerId, NOW);
        verify(points).summarizeOwnerOperations(ownerId);
    }

    @Test
    void cancelledSessionAndExpiredEndTimeStopAccumulatingUsage() {
        when(connectors.summarizeOwnerOperations(ownerId).getTotalConnectors()).thenReturn(2L);
        var windows = List.of(
                window(DAY_START, null, DAY_START.plusSeconds(900), NOW),
                window(DAY_START.plusSeconds(1800), null, null, DAY_START.plusSeconds(2700)));
        when(bookings.findOwnerChargingWindows(ownerId, DAY_START, NOW)).thenReturn(windows);
        var result = useCase.getOperationsSummary();
        assertThat(result.hardware().sessionsToday()).isEqualTo(2);
        assertThat(result.hardware().averageUtilizationPercent()).isEqualByComparingTo("25.00");
    }

    @Test
    void noConnectorsReturnsZero() {
        assertThat(useCase.getOperationsSummary().hardware().averageUtilizationPercent()).isZero();
    }

    @Test
    void midnightWithConnectorsReturnsZero() {
        when(clock.instant()).thenReturn(DAY_START);
        when(stations.summarizeOwnerOperations(ownerId, DAY_START))
                .thenReturn(mock(StationRepository.OwnerOperationsTotals.class));
        when(connectors.summarizeOwnerOperations(ownerId).getTotalConnectors()).thenReturn(2L);
        when(bookings.findOwnerChargingWindows(ownerId, DAY_START, DAY_START)).thenReturn(List.of());
        assertThat(useCase.getOperationsSummary().hardware().averageUtilizationPercent()).isZero();
    }

    private OwnerBookingReadRepository.ChargingWindow window(Instant start, Instant completed,
                                                             Instant cancelled, Instant end) {
        var window = mock(OwnerBookingReadRepository.ChargingWindow.class);
        when(window.getChargingStartedAt()).thenReturn(start);
        when(window.getCompletedAt()).thenReturn(completed);
        when(window.getCancelledAt()).thenReturn(cancelled);
        when(window.getEndAt()).thenReturn(end);
        return window;
    }
}
