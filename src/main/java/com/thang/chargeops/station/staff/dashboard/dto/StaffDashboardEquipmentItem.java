package com.thang.chargeops.station.staff.dashboard.dto;

import java.util.UUID;

public record StaffDashboardEquipmentItem(
        String equipmentType,
        UUID equipmentId,
        String code,
        UUID chargePointId,
        UUID connectorId,
        String chargePointStatus,
        String connectorStatus,
        String reasonCode,
        UUID activeIncidentId
) {}
