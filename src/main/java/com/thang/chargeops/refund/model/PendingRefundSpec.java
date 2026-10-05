package com.thang.chargeops.refund.model;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.profile.entity.UserProfile;

import java.time.Instant;
import java.util.UUID;

/**
 * Server-owned inputs for a refund obligation. Amount and currency are deliberately
 * absent: {@code Refund} derives them from the accepted full-package payment.
 */
public record PendingRefundSpec(
        Booking booking,
        Payment payment,
        PaymentTransaction sourcePaymentTransaction,
        RefundReason reason,
        RefundBasisType basisType,
        UUID basisId,
        Instant decisionAt,
        UserProfile decidedBy
) {
}
