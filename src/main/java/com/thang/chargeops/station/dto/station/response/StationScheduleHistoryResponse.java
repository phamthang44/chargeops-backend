package com.thang.chargeops.station.dto.station.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StationScheduleHistoryResponse(
        UUID scheduleId,
        Instant effectiveFrom,
        Instant effectiveTo,
        String status, // "ACTIVE" | "PENDING" | "EXPIRED" | "CANCELLED"
        boolean open24Hours,
        List<StationPricingResponse.OperatingHourResponse> hours,
        Integer minBookingDurationMin,
        BigDecimal basePriceVnd,
        List<StationPricingResponse.TouRuleResponse> touRules,
        String changedByName,
        Instant changedAt,
        Instant cancelledAt,
        String cancelledByName
) {}
