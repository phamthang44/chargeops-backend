package com.thang.chargeops.station.dto.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OwnerDashboardAttentionPageResponse(
        Instant generatedAt, String kind, List<Item> items, int page, int size, long total
) {
    public record Item(String kind, UUID entityId, String code, UUID stationId, String stationName,
                       String reasonCode, String status, Instant occurredAt) {}
}
