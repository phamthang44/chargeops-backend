package com.thang.chargeops.station.projection.dashboard;

import java.util.UUID;

public record OwnerDashboardStationProjection(
        UUID stationId, String stationCode, String stationName, String status,
        long totalConnectors, long availableConnectors, long chargingConnectors,
        long connectorsNeedingCheck, long sessionsStarted
) {}
