package com.thang.chargeops.payout.projection;

import com.thang.chargeops.common.enums.PaymentEnvironment;

import java.math.BigDecimal;
import java.util.UUID;

/** One reconstructible row of booking finance, never a mutable wallet balance. */
public record OwnerBookingFinancial(
        UUID bookingId, String bookingCode, UUID ownerId, UUID stationId, UUID paymentId,
        PaymentEnvironment environment, String currency, BigDecimal expectedPackageAmount,
        BigDecimal grossCollectedAmount, BigDecimal appliedAmount,
        BigDecimal refundPendingAmount, BigDecimal refundSucceededAmount,
        BigDecimal payoutPendingAmount, BigDecimal paidToOwnerAmount,
        BigDecimal adjustmentDueAmount, boolean incidentHeld,
        boolean eligibleForPayout, BigDecimal eligibleAmount
) {}
