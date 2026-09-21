package com.thang.chargeops.payment.projection;

import java.util.UUID;

public interface OrderPaymentMatchProjection {
    UUID getPaymentId();
    UUID getBookingId();
    UUID getConnectorId();
}
