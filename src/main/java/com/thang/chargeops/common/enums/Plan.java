package com.thang.chargeops.common.enums;

import java.math.BigDecimal;

public enum Plan {
    MONTHLY(BigDecimal.valueOf(500_000)),
    YEARLY(BigDecimal.valueOf(5_000_000));

    private final BigDecimal feeAmount;

    Plan(BigDecimal feeAmount) {
        this.feeAmount = feeAmount;
    }

    public BigDecimal feeAmount() {
        return feeAmount;
    }
}
