package com.thang.chargeops.payment.model;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentEnvironment;

import java.math.BigDecimal;

public record PendingPaymentSpec(
        Booking booking,
        BigDecimal amount,
        PaymentMethod method,
        String provider,
        String receivingAccountRef,
        String currency,
        PaymentEnvironment environment
) {
    public PendingPaymentSpec(
            Booking booking,
            BigDecimal amount,
            PaymentMethod method,
            String provider,
            String receivingAccountRef,
            String currency
    ) {
        this(
                booking,
                amount,
                method,
                provider,
                receivingAccountRef,
                currency,
                method == PaymentMethod.SIMULATOR
                        ? PaymentEnvironment.TEST
                        : PaymentEnvironment.LIVE
        );
    }
}
