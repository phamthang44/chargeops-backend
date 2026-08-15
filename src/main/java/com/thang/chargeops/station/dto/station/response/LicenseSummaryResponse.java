package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.Plan;

import java.time.Instant;

public record LicenseSummaryResponse(
        Plan plan,
        Instant expiresAt
) {
}
