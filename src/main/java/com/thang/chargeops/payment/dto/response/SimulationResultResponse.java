package com.thang.chargeops.payment.dto.response;

import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.payment.dto.request.SimulationRequest;

import java.time.Instant;
import java.util.UUID;

public record SimulationResultResponse(
        SimulationRequest.Outcome outcome,
        boolean duplicateEvent,
        BookingDetailResponse booking,
        Receipt receipt
) {
    public record Receipt(
            UUID transactionId,
            UUID paymentId,
            UUID bookingId,
            String provider,
            String receivingAccountReference,
            String transactionRef,
            long amount,
            String currency,
            Instant receivedAt,
            Instant providerPaidAt,
            ReceiptClassification classification,
            ReconciliationStatus reconciliationStatus,
            long refundedAmount
    ) {
    }

    public enum ReceiptClassification {
        APPLIED,
        DUPLICATE_PACKAGE_PAYMENT,
        LATE,
        UNDERPAID,
        OVERPAID,
        UNMATCHED
    }

    public enum ReconciliationStatus {
        OPEN,
        RESOLVED
    }
}
