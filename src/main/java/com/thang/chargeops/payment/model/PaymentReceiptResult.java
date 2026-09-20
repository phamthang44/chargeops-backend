package com.thang.chargeops.payment.model;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;

import java.util.UUID;

public record PaymentReceiptResult(
        Status status,
        String reason,
        UUID transactionId,
        UUID paymentId,
        UUID bookingId
) {
    public enum Status {
        APPLIED,
        DUPLICATE,
        UNAPPLIED,
        UNMATCHED
    }

    public static PaymentReceiptResult applied(PaymentTransaction tx, Payment payment, Booking booking) {
        return new PaymentReceiptResult(
                Status.APPLIED,
                null,
                tx != null ? tx.getId() : null,
                payment != null ? payment.getId() : null,
                booking != null ? booking.getId() : null
        );
    }

    public static PaymentReceiptResult duplicate(PaymentTransaction tx) {
        return new PaymentReceiptResult(
                Status.DUPLICATE,
                tx != null ? tx.getApplicationReason() : null,
                tx != null ? tx.getId() : null,
                tx != null && tx.getPayment() != null ? tx.getPayment().getId() : null,
                tx != null && tx.getPayment() != null && tx.getPayment().getBooking() != null
                        ? tx.getPayment().getBooking().getId()
                        : null
        );
    }

    public static PaymentReceiptResult unapplied(PaymentTransaction tx, String reason) {
        return new PaymentReceiptResult(
                Status.UNAPPLIED,
                reason,
                tx != null ? tx.getId() : null,
                tx != null && tx.getPayment() != null ? tx.getPayment().getId() : null,
                tx != null && tx.getPayment() != null && tx.getPayment().getBooking() != null
                        ? tx.getPayment().getBooking().getId()
                        : null
        );
    }

    public static PaymentReceiptResult unmatched(PaymentTransaction tx) {
        return new PaymentReceiptResult(
                Status.UNMATCHED,
                tx != null ? tx.getApplicationReason() : "UNMATCHED",
                tx != null ? tx.getId() : null,
                null,
                null
        );
    }
}
