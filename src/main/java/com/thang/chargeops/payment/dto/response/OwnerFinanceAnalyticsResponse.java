package com.thang.chargeops.payment.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record OwnerFinanceAnalyticsResponse(
        String environment, String currency, String timezone, String period, UUID stationId,
        Instant from, Instant to, Instant generatedAt, long grossVnd, long refundedVnd, long netVnd,
        List<Day> daily, List<Station> stations, long stationCount, int stationLimit) {
    public record Day(LocalDate date, long grossVnd, long refundedVnd, long netVnd) {}
    public record Station(UUID stationId, String stationCode, String stationName,
                          long grossVnd, long refundedVnd, long netVnd) {}
}
