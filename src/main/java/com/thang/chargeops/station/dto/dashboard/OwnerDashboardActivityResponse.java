package com.thang.chargeops.station.dto.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record OwnerDashboardActivityResponse(
        Instant generatedAt,
        String timezone,
        String period,
        Instant from,
        Instant to,
        long sessionsStarted,
        long bookingsScheduled,
        Map<String, Long> bookingsByStatus,
        List<DailyActivity> daily
) {
    public record DailyActivity(LocalDate date, long sessionsStarted) {}
}
