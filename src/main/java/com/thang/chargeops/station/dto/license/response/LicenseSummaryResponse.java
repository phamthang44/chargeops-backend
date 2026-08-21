package com.thang.chargeops.station.dto.license.response;

import com.thang.chargeops.common.enums.Plan;

import java.time.Instant;

public record LicenseSummaryResponse(
        Plan plan,
        Instant expiresAt
) {
}
