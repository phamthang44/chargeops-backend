package com.thang.chargeops.booking.dto.response;

import com.thang.chargeops.common.enums.CheckoutStatus;
import com.thang.chargeops.common.enums.PaymentMethod;

import java.time.Instant;

public record CheckoutResponse(
        CheckoutStatus status,
        PaymentMethod method,
        Instant expiresAt,
        String instruction,
        String checkoutReference,
        String checkoutUrl
) {
}
