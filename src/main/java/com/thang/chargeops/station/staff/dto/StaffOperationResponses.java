package com.thang.chargeops.station.staff.dto;

import com.thang.chargeops.common.enums.*;
import java.time.Instant;
import java.util.UUID;

public final class StaffOperationResponses {
    private StaffOperationResponses() { }

    public record Overview(UUID stationId, String name, String address,
                           StationOperationalStatus operationalStatus,
                           long chargePointCount, long connectorCount) { }

    public record ChargePointItem(UUID id, String code, String name, String zoneLabel,
                                  ProvisioningStatus provisioningStatus,
                                  OperationalChargePointStatus operationalStatus, long version) { }

    public record ConnectorItem(UUID id, String code, ConnectorType connectorType,
                                RuntimeStatus runtimeStatus, long version) { }

    public record HistoryItem(UUID id, String dimension, String fromStatus, String toStatus,
                              String reason, EquipmentStatusActorType actorType,
                              String performedByDisplayName, Instant performedAt) { }
}
