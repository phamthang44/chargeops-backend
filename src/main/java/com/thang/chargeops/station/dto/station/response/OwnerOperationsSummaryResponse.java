package com.thang.chargeops.station.dto.station.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record OwnerOperationsSummaryResponse(
        Instant generatedAt, LocalDate date, String timezone,
        Stations stations, Hardware hardware
) {
    public record Stations(long totalStations, long activeStations, long visibleToDrivers,
                           long pendingApproval, long onlineChargePoints, long totalChargePoints) {}

    public record Hardware(long totalConnectors, long availableConnectors, long chargingConnectors,
                           long offlineConnectors, long unavailableConnectors, long sessionsToday,
                           BigDecimal averageUtilizationPercent) {}
}
