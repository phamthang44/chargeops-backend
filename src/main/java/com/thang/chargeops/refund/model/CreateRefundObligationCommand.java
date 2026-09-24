package com.thang.chargeops.refund.model;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.profile.entity.UserProfile;

import java.time.Instant;
import java.util.UUID;

/** Internal, server-derived decision; no client-controlled refund amount or currency. */
public record CreateRefundObligationCommand(
        Booking lockedBooking,
        Payment lockedPayment,
        UUID sourceReceiptId,
        RefundBasisType basisType,
        UUID basisId,
        RefundReason reason,
        UserProfile lockedActor,
        Instant decisionAt
) {
}
