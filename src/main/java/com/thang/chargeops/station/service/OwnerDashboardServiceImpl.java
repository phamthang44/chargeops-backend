package com.thang.chargeops.station.service;

import com.thang.chargeops.booking.repository.OwnerBookingReadRepository;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.dashboard.OwnerDashboardActivityResponse;
import com.thang.chargeops.station.dto.dashboard.OwnerDashboardAttentionPageResponse;
import com.thang.chargeops.station.dto.dashboard.OwnerDashboardStationPageResponse;
import com.thang.chargeops.station.dto.station.response.OwnerOperationsSummaryResponse;
import com.thang.chargeops.station.projection.dashboard.OwnerDashboardAttentionProjection;
import com.thang.chargeops.station.projection.dashboard.OwnerDashboardStationProjection;
import com.thang.chargeops.station.repository.ChargePointRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.repository.OwnerDashboardQueryRepository;
import com.thang.chargeops.station.repository.StationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerDashboardServiceImpl implements OwnerDashboardService {
    private static final ZoneId ZONE = ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);
    private static final List<String> BOOKING_STATUSES = List.of(
            "PENDING", "CONFIRMED", "CHECKED_IN", "CHARGING", "COMPLETED", "EXPIRED", "CANCELLED");
    private static final List<String> ATTENTION_KINDS = List.of("EQUIPMENT", "TICKET", "LICENSE");

    private final CurrentProfileProvider profiles;
    private final OwnerDashboardQueryRepository dashboardQueries;
    private final StationRepository stationRepository;
    private final ChargePointRepository chargePointRepository;
    private final ConnectorRepository connectorRepository;
    private final OwnerBookingReadRepository bookingReadRepository;
    private final Clock applicationClock;

    @Override
    public OwnerOperationsSummaryResponse summary() {
        UUID ownerId = ownerId();
        Instant now = applicationClock.instant();
        LocalDate date = now.atZone(ZONE).toLocalDate();
        Instant dayStart = date.atStartOfDay(ZONE).toInstant();
        var stations = stationRepository.summarizeOwnerOperations(ownerId, now);
        var points = chargePointRepository.summarizeOwnerOperations(ownerId);
        var connectors = connectorRepository.summarizeOwnerOperations(ownerId);
        var windows = bookingReadRepository.findOwnerChargingWindows(ownerId, dayStart, now);

        long sessionsToday = 0;
        BigDecimal chargingMillis = BigDecimal.ZERO;
        for (var window : windows) {
            Instant startedAt = window.getChargingStartedAt();
            if (!startedAt.isBefore(dayStart)) sessionsToday++;
            Instant finishedAt = firstNonNull(window.getCompletedAt(), window.getCancelledAt(), window.getEndAt());
            Instant from = startedAt.isAfter(dayStart) ? startedAt : dayStart;
            Instant to = finishedAt.isBefore(window.getEndAt()) ? finishedAt : window.getEndAt();
            if (to.isAfter(now)) to = now;
            if (to.isAfter(from)) chargingMillis = chargingMillis.add(BigDecimal.valueOf(Duration.between(from, to).toMillis()));
        }
        BigDecimal capacityMillis = BigDecimal.valueOf(Duration.between(dayStart, now).toMillis())
                .multiply(BigDecimal.valueOf(connectors.getTotalConnectors()));
        BigDecimal utilization = capacityMillis.signum() == 0 ? BigDecimal.ZERO.setScale(2)
                : chargingMillis.multiply(BigDecimal.valueOf(100))
                .divide(capacityMillis, 2, RoundingMode.HALF_UP).min(BigDecimal.valueOf(100));

        return new OwnerOperationsSummaryResponse(now, date, ZONE.getId(),
                new OwnerOperationsSummaryResponse.Stations(stations.getTotalStations(), stations.getActiveStations(),
                        stations.getVisibleToDrivers(), stations.getPendingApproval(),
                        points.getOnlineChargePoints(), points.getTotalChargePoints()),
                new OwnerOperationsSummaryResponse.Hardware(connectors.getTotalConnectors(),
                        connectors.getAvailableConnectors(), connectors.getChargingConnectors(),
                        connectors.getOfflineConnectors(), connectors.getUnavailableConnectors(), sessionsToday, utilization));
    }

    @Override
    public OwnerDashboardActivityResponse activity(UUID stationId, String requestedPeriod) {
        String period = normalizePeriod(requestedPeriod);
        PeriodRange range = range(period);
        UUID ownerId = ownerId();
        requireStationScope(ownerId, stationId);
        Instant generatedAt = applicationClock.instant();

        Map<String, Long> statusCounts = new LinkedHashMap<>();
        dashboardQueries.countBookingsByStatus(ownerId, stationId, range.from(), range.to())
                .forEach(row -> statusCounts.put(row.status(), row.total()));
        BOOKING_STATUSES.forEach(status -> statusCounts.putIfAbsent(status, 0L));

        Map<LocalDate, Long> sessionsByDate = new LinkedHashMap<>();
        for (LocalDate day = range.from().atZone(ZONE).toLocalDate(); day.isBefore(range.to().atZone(ZONE).toLocalDate()); day = day.plusDays(1)) {
            sessionsByDate.put(day, 0L);
        }
        dashboardQueries.countSessionsByLocalDate(ownerId, stationId, range.from(), range.to())
                .forEach(row -> sessionsByDate.put(LocalDate.parse(row[0].toString()), ((Number) row[1]).longValue()));
        List<OwnerDashboardActivityResponse.DailyActivity> daily = sessionsByDate.entrySet().stream()
                .map(entry -> new OwnerDashboardActivityResponse.DailyActivity(entry.getKey(), entry.getValue())).toList();

        return new OwnerDashboardActivityResponse(generatedAt, ZONE.getId(), period, range.from(), range.to(),
                dashboardQueries.countSessions(ownerId, stationId, range.from(), range.to()),
                dashboardQueries.countScheduledBookings(ownerId, stationId, range.from(), range.to()),
                statusCounts, daily);
    }

    @Override
    public OwnerDashboardStationPageResponse stations(UUID stationId, String requestedPeriod,
                                                       int requestedPage, int requestedSize) {
        String period = normalizePeriod(requestedPeriod);
        PeriodRange range = range(period);
        UUID ownerId = ownerId();
        requireStationScope(ownerId, stationId);
        PageRequest page = pageRequest(requestedPage, requestedSize);
        List<OwnerDashboardStationPageResponse.Station> items = dashboardQueries
                .findStations(ownerId, stationId, range.from(), range.to(), page.offset(), page.size())
                .stream().map(OwnerDashboardServiceImpl::toStation).toList();
        return new OwnerDashboardStationPageResponse(applicationClock.instant(), period, range.from(), range.to(),
                items, page.number(), page.size(), dashboardQueries.countStations(ownerId, stationId));
    }

    @Override
    public OwnerDashboardAttentionPageResponse attention(UUID stationId, String requestedKind,
                                                          int requestedPage, int requestedSize) {
        String kind = requestedKind == null ? "EQUIPMENT" : requestedKind.toUpperCase(Locale.ROOT);
        if (!ATTENTION_KINDS.contains(kind)) throw new IllegalArgumentException("Unsupported attention kind");
        UUID ownerId = ownerId();
        requireStationScope(ownerId, stationId);
        PageRequest page = pageRequest(requestedPage, requestedSize);
        Instant generatedAt = applicationClock.instant();
        OwnerDashboardQueryRepository.AttentionPage result = dashboardQueries.findAttention(
                ownerId, stationId, kind, generatedAt, page.number(), page.size());
        List<OwnerDashboardAttentionPageResponse.Item> items = result.items().stream()
                .map(item -> toAttentionItem(kind, item)).toList();
        return new OwnerDashboardAttentionPageResponse(generatedAt, kind, items,
                page.number(), page.size(), result.total());
    }

    private UUID ownerId() {
        return profiles.requireProfile().getId();
    }

    private void requireStationScope(UUID ownerId, UUID stationId) {
        if (stationId != null && !dashboardQueries.isOwnedActiveStation(ownerId, stationId)) {
            throw new AccessDeniedException("Station is outside owner scope");
        }
    }

    private static String normalizePeriod(String requestedPeriod) {
        String period = requestedPeriod == null ? "7d" : requestedPeriod.toLowerCase(Locale.ROOT);
        if (!period.equals("7d") && !period.equals("30d")) {
            throw new IllegalArgumentException("period must be 7d or 30d");
        }
        return period;
    }

    private PeriodRange range(String period) {
        LocalDate today = applicationClock.instant().atZone(ZONE).toLocalDate();
        int days = period.equals("30d") ? 30 : 7;
        return new PeriodRange(today.minusDays(days - 1L).atStartOfDay(ZONE).toInstant(),
                today.plusDays(1).atStartOfDay(ZONE).toInstant());
    }

    private static PageRequest pageRequest(int requestedPage, int requestedSize) {
        int page = Math.max(1, requestedPage);
        int size = Math.max(1, Math.min(20, requestedSize));
        int offset = (int) Math.min((long) (page - 1) * size, Integer.MAX_VALUE);
        return new PageRequest(page, size, offset);
    }

    private static OwnerDashboardStationPageResponse.Station toStation(OwnerDashboardStationProjection row) {
        return new OwnerDashboardStationPageResponse.Station(row.stationId(), row.stationCode(), row.stationName(),
                row.status(), row.totalConnectors(), row.availableConnectors(), row.chargingConnectors(),
                row.connectorsNeedingCheck(), row.sessionsStarted());
    }

    private static OwnerDashboardAttentionPageResponse.Item toAttentionItem(String kind,
                                                                             OwnerDashboardAttentionProjection row) {
        return new OwnerDashboardAttentionPageResponse.Item(kind, row.entityId(), row.code(), row.stationId(),
                row.stationName(), row.reasonCode(), row.status(), row.occurredAt());
    }

    private static Instant firstNonNull(Instant first, Instant second, Instant fallback) {
        return first != null ? first : second != null ? second : fallback;
    }

    private record PeriodRange(Instant from, Instant to) {}
    private record PageRequest(int number, int size, int offset) {}
}
