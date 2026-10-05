package com.thang.chargeops.payout.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class PayoutMoney {
    private PayoutMoney() {}

    public static BigDecimal positiveVnd(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.precision() - amount.scale() > 17) {
            throw new IllegalArgumentException("Payout amount must be positive whole VND");
        }
        try {
            BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (normalized.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("Payout amount must be whole VND");
            }
            return normalized;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Payout amount must be whole VND", ex);
        }
    }
}
