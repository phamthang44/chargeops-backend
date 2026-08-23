package com.thang.chargeops.station.dto.chargepoint.response;

import com.thang.chargeops.common.enums.ChargePointStatusDimension;
import com.thang.chargeops.common.enums.EquipmentStatusActorType;

import java.time.Instant;
import java.util.UUID;

public record ChargePointStatusEventResponse(
        UUID id,
        ChargePointStatusDimension statusDimension,
        String fromStatus,
        String toStatus,
        String reason,
        EquipmentStatusActorType actorType,
        UUID performedById,
        String performedByDisplayName,
        Instant performedAt
) {
}
