package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationStatus;

import java.time.Instant;
import java.util.UUID;

public record StationCreatedResponse(
        UUID id,
        String stationCode,
        String name,
        StationStatus status,
        Instant submittedAt) {
}
