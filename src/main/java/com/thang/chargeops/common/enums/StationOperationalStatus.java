package com.thang.chargeops.common.enums;

/**
 * Owner-controlled operating intent. This is independent from the platform
 * lifecycle represented by {@link StationStatus}.
 */
public enum StationOperationalStatus {
    OPERATING,
    PAUSED,
    MAINTENANCE
}
