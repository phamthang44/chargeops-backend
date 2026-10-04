package com.thang.chargeops.booking.dto.response;

import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.refund.model.RefundStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ServiceFailureContextResponse(
        UUID bookingId,
        long bookingVersion,
        long decisionVersion,
        BookingStatus status,
        BookingStatus persistedStatus,
        boolean stateReconciliationPending,
        Instant evaluatedAt,
        Long eligibleAmount,
        String currency,
        List<ServiceFailureDecisionResponse> serviceFailureDecisions,
        RefundSummary refund,
        Availability cancel,
        Availability acceptResponsibility) {

    public record Availability(boolean allowed, String reason) {}

    public record RefundSummary(
            UUID refundId,
            long amount,
            String currency,
            RefundStatus status,
            Boolean requiresOwnerAction
    ) {}
}
