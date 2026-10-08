package com.thang.chargeops.station.event;

/**
 * Dimension of an equipment status change recorded by
 * {@link com.thang.chargeops.station.service.EquipmentStatusHistoryService}.
 *
 * <p>Each constant names exactly one status dimension so that the {@code from}/{@code to}
 * values carried by {@link EquipmentStateChangedEvent} always refer to the matching enum.
 */
public enum EquipmentChangeType {

    /** {@code ProvisioningStatus} transition of a charge point (activate/suspend/reactivate). */
    CHARGE_POINT_PROVISIONING,

    /** {@code OperationalChargePointStatus} transition of a charge point (AVAILABLE/OFFLINE/MAINTENANCE). */
    CHARGE_POINT_OPERATIONAL,

    /** {@code RuntimeStatus} transition of a connector (AVAILABLE/IN_USE/OFFLINE/...). */
    CONNECTOR_RUNTIME
}
