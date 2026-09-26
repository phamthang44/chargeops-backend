package com.thang.chargeops.refund.service;

import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.refund.dto.request.RefundExecutionOutcome;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.refund.executor.RefundExecutionResult;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class RefundExecutionResultHandler {
    private final RefundAttemptRepository attemptRepository;
    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;

    public void apply(
            Refund refund,
            Payment payment,
            RefundAttempt attempt,
            RefundExecutionResult result,
            Instant completedAt
    ) {
        if (result.outcome() == RefundExecutionOutcome.SUCCEEDED) {
            attempt.completeSucceeded(
                    result.providerRefundId(), result.transferReference(), result.performedAt(),
                    result.note(), completedAt
            );
            attemptRepository.saveAndFlush(attempt);
            refund.completeWith(attempt, result.performedAt());
            payment.recordFullRefund(refund.getAmount());
            paymentRepository.flush();
        } else {
            attempt.completeFailed(result.failureCode(), result.note(), result.performedAt(), completedAt);
            attemptRepository.saveAndFlush(attempt);
            refund.requireAdminAction();
        }
        refundRepository.flush();
    }
}
