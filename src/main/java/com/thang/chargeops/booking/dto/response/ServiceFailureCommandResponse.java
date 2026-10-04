package com.thang.chargeops.booking.dto.response;

import com.thang.chargeops.common.enums.BookingStatus;
import java.util.UUID;

public record ServiceFailureCommandResponse(
        UUID bookingId,
        long bookingVersion,
        BookingStatus status,
        BookingCancellationReason cancellationReason,
        boolean stateReconciliationPending,
        long decisionVersion,
        ServiceFailureDecisionResponse decision,
        ServiceFailureContextResponse.RefundSummary refund
) {}
