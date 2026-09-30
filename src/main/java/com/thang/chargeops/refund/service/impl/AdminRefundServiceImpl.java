package com.thang.chargeops.refund.service.impl;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.constant.LogConstant;
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
import com.thang.chargeops.refund.dto.response.RefundDetailResponse;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.refund.executor.RefundExecutionCommand;
import com.thang.chargeops.refund.executor.RefundExecutionResult;
import com.thang.chargeops.refund.executor.RefundExecutor;
import com.thang.chargeops.refund.executor.RefundExecutorRegistry;
import com.thang.chargeops.refund.factory.RefundAttemptFactory;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.projection.RefundExecutionRouteProjection;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;

import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.AdminRefundService;
import com.thang.chargeops.refund.service.RefundDetailAssembler;
import com.thang.chargeops.refund.service.RefundExecutionPayloadHasher;
import com.thang.chargeops.refund.service.RefundExecutionResultHandler;
import com.thang.chargeops.station.repository.ConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminRefundServiceImpl implements AdminRefundService {
    private final CurrentProfileProvider currentProfileProvider;
    private final UserProfileRepository userProfileRepository;
    private final ConnectorRepository connectorRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRepository refundRepository;
    private final RefundAttemptRepository refundAttemptRepository;
    private final RefundExecutorRegistry executorRegistry;
    private final RefundDetailAssembler assembler;
    private final RefundExecutionResultHandler resultHandler;
    private final Clock applicationClock;

    @Override
    @Transactional(readOnly = true)
    public Page<RefundDetailResponse> list(
            RefundStatus status,
            String search,
            int pageNo,
            int pageSize
    ) {
        Specification<Refund> specification = Specification.unrestricted();
        if (status != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        String normalizedSearch = normalize(search);
        if (normalizedSearch != null) {
            specification = specification.and(searchSpecification(normalizedSearch));
        }
        PageRequest pageable = PageRequest.of(
                pageNo - 1,
                pageSize,
                Sort.by(Sort.Order.desc("decisionAt"), Sort.Order.desc("id"))
        );
        return refundRepository.findAll(specification, pageable)
                .map(refund -> assembler.assemble(refund, List.of()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> counts() {
        return Map.of(
                RefundStatus.PENDING.name(), refundRepository.countByStatus(RefundStatus.PENDING),
                RefundStatus.SUCCEEDED.name(), refundRepository.countByStatus(RefundStatus.SUCCEEDED)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public RefundDetailResponse get(UUID refundId) {
        return assembleDetail(requireRefund(refundId));
    }

    @Override
    @Transactional
    public RefundDetailResponse execute(
            UUID refundId,
            UUID requestKey,
            ExecuteRefundRequest request
    ) {
        Objects.requireNonNull(refundId, "refundId must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");
        Objects.requireNonNull(request, "request must not be null");
        UUID actorId = currentProfileProvider.requireProfileId();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "execute", actorId,
                "refundId=" + refundId + ", requestKey=" + requestKey
                        + ", executionMode=" + request.executionMode()
                        + ", expectedVersion=" + request.expectedVersion());

        String payloadHash = RefundExecutionPayloadHasher.sha256(refundId, request);
        Optional<RefundAttempt> fastReplay = refundAttemptRepository.findByRefundIdAndRequestKey(refundId, requestKey);
        if (fastReplay.isPresent()) {
            log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "execute", actorId,
                    "refundId=" + refundId + ", requestKey=" + requestKey + ", replay=true, phase=fast");
            return replay(fastReplay.get(), payloadHash);
        }

        RefundExecutor executor = executorRegistry.require(request.executionMode());
        UserProfile actor = currentProfileProvider.requireProfile();
        UserProfile lockedActor = userProfileRepository.findByIdWithLock(actor.getId())
                .orElseThrow(() -> new AppException(ProfileErrorCode.PROFILE_NOT_FOUND));

        Optional<RefundAttempt> lockedReplay = refundAttemptRepository.findByRefundIdAndRequestKey(refundId, requestKey);
        if (lockedReplay.isPresent()) {
            log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "execute", lockedActor.getId(),
                    "refundId=" + refundId + ", requestKey=" + requestKey
                            + ", replay=true, phase=actor_locked");
            return replay(lockedReplay.get(), payloadHash);
        }

        RefundExecutionRouteProjection route = refundRepository.findExecutionRouteById(refundId)
                .orElseThrow(() -> notFound(refundId));

        connectorRepository.findByIdWithLock(route.getConnectorId())
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Booking booking = bookingRepository.findByIdWithLock(route.getBookingId())
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Payment payment = paymentRepository.findByIdWithLock(route.getPaymentId())
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        paymentTransactionRepository.findByIdWithLock(route.getSourcePaymentTransactionId())
                .orElseThrow(() -> new AppException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Refund refund = refundRepository.findByIdWithLock(refundId)
                .orElseThrow(() -> notFound(refundId));

        Optional<RefundAttempt> refundLockedReplay = refundAttemptRepository.findByRefundIdAndRequestKey(refundId, requestKey);
        if (refundLockedReplay.isPresent()) {
            log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "execute", lockedActor.getId(),
                    "refundId=" + refundId + ", requestKey=" + requestKey
                            + ", replay=true, phase=refund_locked");
            return replay(refundLockedReplay.get(), payloadHash);
        }

        if (!Objects.equals(refund.getVersion(), request.expectedVersion())) {
            throw new AppException(RefundErrorCode.VERSION_CONFLICT);
        }
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            throw new AppException(RefundErrorCode.EXECUTION_CONFLICT, "Refund already succeeded");
        }

        Instant executionAt = applicationClock.instant();
        int sequenceNo = Math.toIntExact(refundAttemptRepository.countByRefundId(refundId) + 1);
        RefundAttempt attempt = RefundAttemptFactory.adminAttempt(
                refund, sequenceNo, request.executionMode(), requestKey, payloadHash, lockedActor, executionAt
        );

        RefundExecutionResult result = executor.execute(new RefundExecutionCommand(
                refundId,
                requestKey,
                request,
                executionAt
        ));

        resultHandler.apply(refund, payment, attempt, result, executionAt);

        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "execute", lockedActor.getId(),
                "refundId=" + refundId + ", attemptId=" + attempt.getId()
                        + ", executionMode=" + request.executionMode() + ", outcome=" + result.outcome()
                        + ", refundStatus=" + refund.getStatus()
                        + ", adminActionRequired=" + refund.isRequiresAdminAction());

        return assembleDetail(refund);
    }

    private RefundDetailResponse replay(RefundAttempt attempt, String payloadHash) {
        if (!Objects.equals(attempt.getPayloadHash(), payloadHash)) {
            throw new AppException(RefundErrorCode.REQUEST_CONFLICT);
        }
        return assembleDetail(requireRefund(attempt.getRefund().getId()));
    }

    private RefundDetailResponse assembleDetail(Refund refund) {
        List<RefundAttempt> attempts = refundAttemptRepository.findByRefundIdOrderBySequenceNoAsc(refund.getId());
        return assembler.assemble(refund, attempts);
    }

    private Refund requireRefund(UUID refundId) {
        return refundRepository.findById(refundId).orElseThrow(() -> notFound(refundId));
    }

    private static AppException notFound(UUID refundId) {
        return new AppException(CommonErrorCode.RESOURCE_NOT_FOUND, "Refund not found: " + refundId);
    }

    private static Specification<Refund> searchSpecification(String search) {
        try {
            UUID id = UUID.fromString(search);
            return (root, query, cb) -> cb.or(
                    cb.equal(root.get("id"), id),
                    cb.equal(root.get("booking").get("id"), id),
                    cb.equal(root.get("booking").get("driver").get("id"), id)
            );
        } catch (IllegalArgumentException ignored) {
            String pattern = "%" + search.toLowerCase() + "%";
            return (root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("booking").get("bookingCode")), pattern),
                    cb.like(cb.lower(root.get("booking").get("driver").get("displayName")), pattern),
                    cb.like(cb.lower(root.get("booking").get("stationNameSnapshot")), pattern)
            );
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

