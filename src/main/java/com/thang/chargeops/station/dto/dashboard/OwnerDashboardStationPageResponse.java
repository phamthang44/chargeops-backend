package com.thang.chargeops.station.dto.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OwnerDashboardStationPageResponse(
        Instant generatedAt, String period, Instant from, Instant to,
        List<Station> items, int page, int size, long total
) {
    public record Station(UUID stationId, String stationCode, String stationName, String status,
                          long totalConnectors, long availableConnectors, long chargingConnectors,
                          long connectorsNeedingCheck, long sessionsStarted) {}
}
