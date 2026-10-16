package com.thang.chargeops.station.projection.dashboard;

import java.time.Instant;
import java.util.UUID;

public record OwnerDashboardAttentionProjection(
        UUID entityId, String code, UUID stationId, String stationName,
        String reasonCode, String status, Instant occurredAt
) {}
