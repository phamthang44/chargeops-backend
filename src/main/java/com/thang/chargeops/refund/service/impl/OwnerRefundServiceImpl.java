package com.thang.chargeops.refund.service.impl;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.dto.request.ExecuteRefundRequest;
import com.thang.chargeops.refund.dto.request.RefundExecutionOutcome;
import com.thang.chargeops.refund.dto.response.OwnerRefundResponse;
import com.thang.chargeops.refund.dto.response.RefundDetailResponse;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.refund.executor.RefundExecutionCommand;
import com.thang.chargeops.refund.executor.RefundExecutorRegistry;
import com.thang.chargeops.refund.factory.RefundAttemptFactory;
import com.thang.chargeops.refund.model.RefundExecutionMode;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.projection.RefundExecutionRouteProjection;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.OwnerRefundService;
import com.thang.chargeops.refund.service.RefundDetailAssembler;
import com.thang.chargeops.refund.service.RefundExecutionPayloadHasher;
import com.thang.chargeops.refund.service.RefundExecutionResultHandler;
import com.thang.chargeops.station.repository.ConnectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OwnerRefundServiceImpl implements OwnerRefundService {
    private static final String RETRY_NOTE = "Owner requested Simulator refund retry";

    private final CurrentProfileProvider currentProfileProvider;
    private final UserProfileRepository userProfileRepository;
    private final ConnectorRepository connectorRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRepository refundRepository;
    private final RefundAttemptRepository attemptRepository;
    private final RefundExecutorRegistry executorRegistry;
    private final RefundExecutionResultHandler resultHandler;
    private final RefundDetailAssembler assembler;
    private final Clock applicationClock;

    @Override
    @Transactional(readOnly = true)
    public Page<OwnerRefundResponse> list(RefundStatus status, int pageNo, int pageSize) {
        UUID ownerId = currentProfileProvider.requireProfileId();
        Specification<Refund> scope = (root, query, cb) -> cb.and(
                cb.equal(root.get("booking").get("connector").get("chargePoint")
                        .get("station").get("owner").get("id"), ownerId),
                cb.equal(root.get("payment").get("environment"), PaymentEnvironment.SIMULATOR));
        if (status != null) {
            scope = scope.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return refundRepository.findAll(scope, PageRequest.of(pageNo - 1, pageSize,
                        Sort.by(Sort.Order.desc("decisionAt"), Sort.Order.desc("id"))))
                .map(refund -> toOwnerResponse(refund, List.of()));
    }

    @Override
    @Transactional(readOnly = true)
    public OwnerRefundResponse get(UUID refundId) {
        UUID ownerId = currentProfileProvider.requireProfileId();
        Refund refund = requireOwnedRefund(refundId, ownerId);
        return detail(refund);
    }

    @Override
    @Transactional
    public OwnerRefundResponse retry(UUID refundId, UUID requestKey, long expectedVersion) {
        Objects.requireNonNull(refundId, "refundId must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");
        UserProfile actor = currentProfileProvider.requireProfile();
        UserProfile lockedOwner = userProfileRepository.findByIdWithLock(actor.getId())
                .orElseThrow(() -> new AppException(ProfileErrorCode.PROFILE_NOT_FOUND));

        RefundExecutionRouteProjection route = refundRepository.findExecutionRouteById(refundId)
                .orElseThrow(OwnerRefundServiceImpl::notFound);
        connectorRepository.findByIdWithLock(route.getConnectorId())
                .orElseThrow(OwnerRefundServiceImpl::notFound);
        Booking booking = bookingRepository.findByIdWithLock(route.getBookingId())
                .orElseThrow(OwnerRefundServiceImpl::notFound);
        if (!ownerId(booking).equals(lockedOwner.getId())) {
            throw notFound();
        }
        Payment payment = paymentRepository.findByIdWithLock(route.getPaymentId())
                .orElseThrow(OwnerRefundServiceImpl::notFound);
        paymentTransactionRepository.findByIdWithLock(route.getSourcePaymentTransactionId())
                .orElseThrow(OwnerRefundServiceImpl::notFound);
        Refund refund = refundRepository.findByIdWithLock(refundId)
                .orElseThrow(OwnerRefundServiceImpl::notFound);

        if (payment.getEnvironment() != PaymentEnvironment.SIMULATOR) {
            throw new AppException(RefundErrorCode.EXECUTION_CONFLICT,
                    "Only Simulator refunds can be retried in the demo");
        }
        ExecuteRefundRequest request = new ExecuteRefundRequest(expectedVersion,
                RefundExecutionMode.SIMULATOR, RefundExecutionOutcome.SUCCEEDED,
                null, null, RETRY_NOTE);
        String payloadHash = RefundExecutionPayloadHasher.sha256(refundId, request);
        var replay = attemptRepository.findByRefundIdAndRequestKey(refundId, requestKey);
        if (replay.isPresent()) {
            if (!Objects.equals(replay.orElseThrow().getPayloadHash(), payloadHash)) {
                throw new AppException(RefundErrorCode.REQUEST_CONFLICT);
            }
            return detail(refund);
        }
        if (!Objects.equals(refund.getVersion(), expectedVersion)) {
            throw new AppException(RefundErrorCode.VERSION_CONFLICT);
        }
        if (refund.getStatus() != RefundStatus.PENDING || !refund.isRequiresAdminAction()) {
            throw new AppException(RefundErrorCode.EXECUTION_CONFLICT,
                    "Only a failed Simulator refund may be retried by its Owner");
        }
        long attemptCount = attemptRepository.countByRefundId(refundId);
        if (attemptCount == 0) {
            throw new AppException(RefundErrorCode.EXECUTION_CONFLICT,
                    "The first Simulator attempt is still pending");
        }
        Instant at = applicationClock.instant();
        RefundAttempt attempt = RefundAttemptFactory.ownerRetry(refund,
                Math.toIntExact(attemptCount + 1), requestKey, payloadHash, lockedOwner, at);
        var result = executorRegistry.require(RefundExecutionMode.SIMULATOR)
                .execute(new RefundExecutionCommand(refundId, requestKey, request, at));
        resultHandler.apply(refund, payment, attempt, result, at);
        return detail(refund);
    }

    private Refund requireOwnedRefund(UUID refundId, UUID ownerId) {
        Refund refund = refundRepository.findById(refundId).orElseThrow(OwnerRefundServiceImpl::notFound);
        if (!ownerId(refund.getBooking()).equals(ownerId)
                || refund.getPayment().getEnvironment() != PaymentEnvironment.SIMULATOR) {
            throw notFound();
        }
        return refund;
    }

    private OwnerRefundResponse detail(Refund refund) {
        return toOwnerResponse(refund, attemptRepository.findByRefundIdOrderBySequenceNoAsc(refund.getId()));
    }

    private OwnerRefundResponse toOwnerResponse(Refund refund, List<RefundAttempt> attempts) {
        RefundDetailResponse base = assembler.assemble(refund, attempts);
        return new OwnerRefundResponse(base.refundId(), base.bookingId(), base.bookingCode(),
                refund.getBooking().getConnector().getChargePoint().getStation().getId(),
                base.amount(), base.currency(), base.reason(), base.status(),
                base.requiresAdminAction(), base.version(), base.decisionAt(),
                base.completedAt(), base.attempts());
    }

    private UUID ownerId(Booking booking) {
        return booking.getConnector().getChargePoint().getStation().getOwner().getId();
    }

    private static AppException notFound() {
        return new AppException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
}
