package com.thang.chargeops.station.exception.violation;

public enum ChargePointViolation {
    STATION_REQUIRED,
    CODE_REQUIRED,
    MAX_POWER_OUT_OF_RANGE,
    CONNECTOR_POWER_EXCEEDS_MAX_POWER,
    STATUS_REQUIRED,
    INVALID_PROVISIONING_TRANSITION
}
