package com.thang.chargeops.station.dto.chargepoint.response;

import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ChargePointDetailResponse(
        UUID id,
        UUID stationId,
        String chargePointCode,
        String name,
        String zoneLabel,
        BigDecimal maxPowerKw,
        ProvisioningStatus provisioningStatus,
        OperationalChargePointStatus operationalStatus,
        Instant createdAt
) {
}
