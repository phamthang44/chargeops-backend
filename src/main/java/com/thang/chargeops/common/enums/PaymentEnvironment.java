package com.thang.chargeops.common.enums;

/**
 * Separates non-monetary demo/sandbox records from real financial records.
 * Owner revenue, payout and accounting queries must include LIVE only.
 */
public enum PaymentEnvironment {
    TEST,
    LEGACY,
    LIVE
}
