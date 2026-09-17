package com.thang.chargeops.booking.dto.response;

import lombok.Builder;

import java.util.UUID;

/** Immutable station and connector labels captured for a booking read model. */
@Builder
public record StationSnapshotResponse(
        UUID stationId,
        String stationName,
        String stationAddress,
        String chargePointCode,
        UUID connectorId,
        String connectorCode
) {
}
