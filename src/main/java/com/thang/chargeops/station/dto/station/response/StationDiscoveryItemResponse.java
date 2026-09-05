package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.StationOperatingState;
import com.thang.chargeops.common.enums.StationOperationalStatus;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record StationDiscoveryItemResponse(
        UUID id,
        String name,
        String address,
        String provinceName,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal distanceKm,
        String primaryImageUrl,
        BigDecimal priceFromVndPerKwh,
        BigDecimal maxPowerKw,
        Set<ConnectorType> connectorTypes,
        int totalConnectorCount,
        int availableConnectorCount,
        StationOperationalStatus operationalStatus,
        String operationalStatusReason,
        boolean openNow,
        StationOperatingState operatingState,
        boolean scheduleConfigured
) {
}
