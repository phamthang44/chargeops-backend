package com.thang.chargeops.refund.projection;

import java.util.UUID;

/** Scalar route used to preserve the global lock hierarchy without preloading Refund. */
public interface RefundExecutionRouteProjection {
    UUID getRefundId();
    UUID getBookingId();
    UUID getConnectorId();
    UUID getPaymentId();
    UUID getSourcePaymentTransactionId();
}

