package com.thang.chargeops.payment.dto.response;

import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.refund.model.RefundStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only simulated cash evidence for one Owner booking, not a spendable wallet. */
public record OwnerFinanceBookingResponse(
        UUID bookingId,
        String bookingCode,
        UUID stationId,
        String stationName,
        PaymentStatus paymentStatus,
        long collectedAmount,
        long refundedAmount,
        long pendingRefundAmount,
        long netRecordedAmount,
        RefundStatus refundStatus,
        Instant paidAt,
        List<Receipt> receipts
) {
    public record Receipt(UUID receiptId, String transactionRef, long amount,
                          Instant receivedAt) {
    }
}
