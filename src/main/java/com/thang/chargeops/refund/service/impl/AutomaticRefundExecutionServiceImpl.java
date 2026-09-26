package com.thang.chargeops.refund.service.impl;

import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.refund.dto.request.ExecuteRefundRequest;
import com.thang.chargeops.refund.dto.request.RefundExecutionOutcome;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.refund.entity.RefundAutoDispatch;
import com.thang.chargeops.refund.executor.RefundExecutionCommand;
import com.thang.chargeops.refund.executor.RefundExecutionResult;
import com.thang.chargeops.refund.executor.RefundExecutor;
import com.thang.chargeops.refund.executor.RefundExecutorRegistry;
import com.thang.chargeops.refund.model.*;
import com.thang.chargeops.refund.projection.RefundExecutionRouteProjection;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;
import com.thang.chargeops.refund.repository.RefundAutoDispatchRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.AutomaticRefundExecutionService;
import com.thang.chargeops.refund.service.RefundExecutionPayloadHasher;
import com.thang.chargeops.station.repository.ConnectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AutomaticRefundExecutionServiceImpl implements AutomaticRefundExecutionService {
    private static final String AUTO_NOTE = "Automatic 100% grace-period refund";

    private final ConnectorRepository connectorRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRepository refundRepository;
    private final RefundAttemptRepository refundAttemptRepository;
    private final RefundAutoDispatchRepository autoDispatchRepository;
    private final RefundExecutorRegistry executorRegistry;
    private final Clock applicationClock;

    @Override
    @Transactional
    public boolean processFirstAttempt(UUID refundId) {
        Objects.requireNonNull(refundId, "refundId must not be null");

        RefundExecutionRouteProjection route = refundRepository.findExecutionRouteById(refundId)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));

        connectorRepository.findByIdWithLock(route.getConnectorId())
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        bookingRepository.findByIdWithLock(route.getBookingId())
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Payment payment = paymentRepository.findByIdWithLock(route.getPaymentId())
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        paymentTransactionRepository.findByIdWithLock(route.getSourcePaymentTransactionId())
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));

        RefundAutoDispatch dispatch = autoDispatchRepository.findByRefundIdWithLock(refundId)
                .orElseThrow(() -> new AppException(RefundErrorCode.EXECUTION_CONFLICT));
        if (dispatch.getStatus() == RefundAutoDispatchStatus.PROCESSED) {
            return false;
        }

        Refund refund = refundRepository.findByIdWithLock(refundId)
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Instant executionAt = applicationClock.instant();

        if (refund.getExecutionPolicy() != RefundExecutionPolicy.AUTO_FIRST_ATTEMPT
                || refund.getReason() != RefundReason.VOLUNTARY_GRACE) {
            throw new AppException(RefundErrorCode.EXECUTION_CONFLICT, "Refund is not auto-execution eligible");
        }
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            dispatch.markProcessed(executionAt);
            return false;
        }
        if (refundAttemptRepository.countByRefundId(refundId) > 0) {
            refund.requireAdminAction();
            dispatch.markProcessed(executionAt);
            return false;
        }

        ExecuteRefundRequest request = new ExecuteRefundRequest(
                refund.getVersion(),
                RefundExecutionMode.SIMULATOR,
                RefundExecutionOutcome.SUCCEEDED,
                null,
                null,
                AUTO_NOTE
        );
        String payloadHash = RefundExecutionPayloadHasher.sha256(refundId, request);
        RefundExecutor executor = executorRegistry.require(RefundExecutionMode.SIMULATOR);
        RefundAttempt attempt = RefundAttempt.start(new PendingRefundAttemptSpec(
                refund,
                1,
                RefundExecutionMode.SIMULATOR,
                dispatch.getRequestKey(),
                payloadHash,
                "refund-auto-first-attempt:" + refundId,
                RefundExecutionTrigger.SYSTEM_POLICY,
                null,
                executionAt
        ));

        RefundExecutionResult result = executor.execute(new RefundExecutionCommand(
                refundId,
                dispatch.getRequestKey(),
                request,
                executionAt
        ));
        if (result.outcome() == RefundExecutionOutcome.SUCCEEDED) {
            attempt.completeSucceeded(
                    result.providerRefundId(), result.transferReference(), result.performedAt(),
                    result.note(), executionAt
            );
            refundAttemptRepository.saveAndFlush(attempt);
            refund.completeWith(attempt, result.performedAt());
            payment.recordFullRefund(refund.getAmount());
            paymentRepository.flush();
            refundRepository.flush();
        } else {
            attempt.completeFailed(result.failureCode(), result.note(), result.performedAt(), executionAt);
            refundAttemptRepository.saveAndFlush(attempt);
            refund.requireAdminAction();
            refundRepository.flush();
        }
        dispatch.markProcessed(executionAt);
        return true;
    }
}
