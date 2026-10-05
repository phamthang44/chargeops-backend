package com.thang.chargeops.payment.projection;

import java.math.BigDecimal;

public interface OwnerFinanceTotalsProjection {
    BigDecimal getGrossAmount();
    BigDecimal getRefundedAmount();
    long getPaymentCount();
    long getPaidCount();
}
