package com.thang.chargeops.station.dto.chargepoint.response;

import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.RuntimeStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ConnectorDetailResponse(
        UUID id,
        UUID chargePointId,
        String connectorCode,
        ConnectorType connectorType,
        BigDecimal powerKw,
        ChargerType chargerType,
        RuntimeStatus runtimeStatus,
        Instant createdAt
) {
}
