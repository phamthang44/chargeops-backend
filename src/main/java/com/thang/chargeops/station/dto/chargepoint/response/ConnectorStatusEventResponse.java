package com.thang.chargeops.station.dto.chargepoint.response;

import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.RuntimeStatus;

import java.time.Instant;
import java.util.UUID;

public record ConnectorStatusEventResponse(
        UUID id,
        RuntimeStatus fromStatus,
        RuntimeStatus toStatus,
        String reason,
        EquipmentStatusActorType actorType,
        UUID performedById,
        String performedByDisplayName,
        Instant performedAt
) {
}
