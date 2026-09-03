package com.thang.chargeops.station.dto.station.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StationScheduleHistoryResponse(
        UUID scheduleId,
        Instant effectiveFrom,
        Instant effectiveTo,
        String status, // "ACTIVE" | "EXPIRED"
        boolean open24Hours,
        List<StationPricingResponse.OperatingHourResponse> hours,
        String changedByName,
        Instant changedAt
) {}
