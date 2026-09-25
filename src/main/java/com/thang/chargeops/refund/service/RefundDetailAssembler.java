package com.thang.chargeops.refund.service;

import com.thang.chargeops.refund.dto.response.RefundAttemptResponse;
import com.thang.chargeops.refund.dto.response.RefundDetailResponse;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RefundDetailAssembler {

    public RefundDetailResponse assemble(Refund refund, List<RefundAttempt> attempts) {
        RefundAttempt successfulAttempt = refund.getSuccessfulAttempt();
        return new RefundDetailResponse(
                refund.getId(),
                refund.getBooking().getId(),
                refund.getBooking().getBookingCode(),
                refund.getBooking().getDriver().getId(),
                refund.getBooking().getDriver().getDisplayName(),
                refund.getBooking().getStationNameSnapshot(),
                refund.getAmount().longValueExact(),
                refund.getCurrency(),
                refund.getReason(),
                refund.getBasisType(),
                refund.getBasisId(),
                refund.getStatus(),
                refund.getVersion(),
                refund.getDecisionAt(),
                refund.getDecidedBy().getId(),
                successfulAttempt == null ? null : successfulAttempt.getId(),
                successfulAttempt == null ? null : successfulAttempt.getTransferReference(),
                refund.getCompletedAt(),
                attempts.stream().map(this::assembleAttempt).toList()
        );
    }

    private RefundAttemptResponse assembleAttempt(RefundAttempt attempt) {
        return new RefundAttemptResponse(
                attempt.getId(),
                attempt.getSequenceNo(),
                attempt.getExecutionMode(),
                attempt.getStatus(),
                attempt.getTransferReference(),
                attempt.getFailureCode(),
                attempt.getNote(),
                attempt.getStartedAt(),
                attempt.getPerformedAt(),
                attempt.getCompletedAt(),
                attempt.getPerformedBy().getId()
        );
    }
}

