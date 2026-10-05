package com.thang.chargeops.refund.projection;

import java.math.BigDecimal;

public interface OwnerRefundTotalsProjection {
    long getTotalCount();
    long getPendingCount();
    long getSucceededCount();
    long getNeedsAdminCount();
    BigDecimal getTotalAmount();
    BigDecimal getPendingAmount();
}
