package com.thang.chargeops.station.dto.chargepoint.response;

import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.incident.IncidentAuditReason;

import java.time.Instant;
import java.util.UUID;

/**
 * One connector runtime-status transition.
 *
 * <p>{@code reason} is the raw audit string; when it references a connector incident the
 * {@code incidentAction}/{@code incidentId}/{@code incidentDetail} triple carries the
 * structured reference plus the full, untruncated text resolved from
 * {@code connector_incidents} for display.
 */
public record ConnectorStatusEventResponse(
        UUID id,
        RuntimeStatus fromStatus,
        RuntimeStatus toStatus,
        String reason,
        IncidentAuditReason.Action incidentAction,
        UUID incidentId,
        String incidentDetail,
        EquipmentStatusActorType actorType,
        UUID performedById,
        String performedByDisplayName,
        Instant performedAt
) {
}
