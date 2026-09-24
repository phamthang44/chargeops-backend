package com.thang.chargeops.common.enums;

/**
 * Separates offline simulation, provider sandbox, legacy and real financial records.
 * Financial queries must select exactly one environment; production money movement uses LIVE only.
 */
public enum PaymentEnvironment {
    SIMULATOR,
    TEST,
    LEGACY,
    LIVE
}
