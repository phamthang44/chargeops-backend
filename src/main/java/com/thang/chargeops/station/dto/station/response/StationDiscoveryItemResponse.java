package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.ConnectorType;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record StationDiscoveryItemResponse(
        UUID id,
        String name,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal distanceKm,
        String primaryImageUrl,
        BigDecimal priceFromVndPerKwh,
        BigDecimal maxPowerKw,
        Set<ConnectorType> connectorTypes,
        int totalConnectorCount,
        int availableConnectorCount,
        boolean openNow
) {
}
