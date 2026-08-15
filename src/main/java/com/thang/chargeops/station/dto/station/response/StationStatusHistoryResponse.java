package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationStatusEventType;

import java.time.Instant;
import java.util.UUID;

public record StationStatusHistoryResponse(
        UUID id,
        UUID stationId,
        String stationCode,
        String stationName,
        StationStatusEventType eventType,
        StationStatus fromStatus,
        StationStatus toStatus,
        String reason,
        UUID performedById,
        String performedByName,
        String performedByEmail,
        String performedByRole,
        Instant performedAt
) {
}
