package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.incident.IncidentFinancialEvidenceReader;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandPayloadHasher;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.command.BookingCommandRepository;
import com.thang.chargeops.booking.decision.BookingServiceFailureDecision;
import com.thang.chargeops.booking.decision.BookingServiceFailureDecisionRepository;
import com.thang.chargeops.booking.decision.ServiceFailureDecisionKind;
import com.thang.chargeops.booking.dto.request.OwnerAcceptStationFailureRequest;
import com.thang.chargeops.booking.dto.request.OwnerCancelBookingRequest;
import com.thang.chargeops.booking.dto.response.BookingCancellationReason;
import com.thang.chargeops.booking.dto.response.ServiceFailureCommandResponse;
import com.thang.chargeops.booking.dto.response.ServiceFailureContextResponse;
import com.thang.chargeops.booking.dto.response.ServiceFailureDecisionResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusActorType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.policy.BookingEffectiveStatePolicy;
import com.thang.chargeops.booking.policy.ServiceFailureEligibilityPolicy;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.impl.BookingNoShowServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.refund.model.RefundBasisType;
import com.thang.chargeops.refund.model.RefundReason;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BaseErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.model.CreateRefundObligationCommand;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.RefundObligationService;
import com.thang.chargeops.station.repository.ConnectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.thang.chargeops.exception.errorcode.ServiceFailureErrorCode.DECISION_CHANGED;
import static com.thang.chargeops.exception.errorcode.ServiceFailureErrorCode.RECONCILIATION_REQUIRED;
import static com.thang.chargeops.exception.errorcode.ServiceFailureErrorCode.RESPONSIBILITY_ALREADY_ACCEPTED;
import static com.thang.chargeops.exception.errorcode.ServiceFailureErrorCode.VERSION_CONFLICT;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class BookingServiceFailureService {

    private final CurrentProfileProvider currentProfileProvider;
    private final UserProfileRepository profiles;
    private final ConnectorRepository connectors;
    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final PaymentTransactionRepository receipts;
    private final RefundRepository refunds;
    private final BookingCommandRegistry commands;
    private final BookingCommandRepository commandRepository;
    private final BookingServiceFailureDecisionRepository decisions;
    private final BookingStatusHistoryRecorder history;
    private final RefundObligationService obligations;
    private final BookingNoShowServiceImpl noShow;
    private final ServiceFailureEligibilityPolicy eligibility;
    private final BookingEffectiveStatePolicy effectiveState;
    private final Clock applicationClock;
    private final IncidentFinancialEvidenceReader incidentEvidence;
    private final com.thang.chargeops.notification.outbox.BookingConfirmationOutboxWriter notificationOutbox;
    private final com.thang.chargeops.notification.outbox.BookingEventOutboxWriter eventOutbox;

    // =========================================================================
    // Public Command API
    // =========================================================================

    /**
     * Owner cancels a booking due to station failure before check-in.
     */
    @Transactional
    public ServiceFailureCommandResponse cancel(UUID bookingId, UUID key, OwnerCancelBookingRequest request) {
        validateRequestVersionAndReason(request.expectedVersion(), request.reason());
        return execute(new ServiceFailureExecutionRequest(
                bookingId,
                key,
                request.expectedVersion(),
                null,
                null,
                request.reason(),
                true
        ));
    }

    /**
     * Owner accepts responsibility and initiates refund for a booking affected by station failure.
     */
    @Transactional
    public ServiceFailureCommandResponse accept(UUID bookingId, UUID key, OwnerAcceptStationFailureRequest request) {
        validateRequestVersionAndReason(request.expectedVersion(), request.reason());
        if (request.expectedDecisionVersion() == null
                || request.expectedDecisionVersion() < 0
                || request.affectedAt() == null) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
        return execute(new ServiceFailureExecutionRequest(
                bookingId,
                key,
                request.expectedVersion(),
                request.expectedDecisionVersion(),
                request.affectedAt(),
                request.reason(),
                false
        ));
    }

    /**
     * Fetches current station failure context, policy evaluation, and action availability for owner.
     */
    @Transactional(readOnly = true)
    public ServiceFailureContextResponse context(UUID bookingId) {
        UserProfile owner = currentProfileProvider.requireProfile();
        requireActive(owner);

        var route = bookings.findServiceFailureRoute(bookingId)
                .orElseThrow(BookingServiceFailureService::notFound);
        if (!Objects.equals(route.getOwnerId(), owner.getId())) {
            throw notFound();
        }

        Booking booking = bookings.findById(bookingId)
                .orElseThrow(BookingServiceFailureService::notFound);
        Payment payment = payments.findByBookingId(bookingId)
                .orElseThrow(() -> new AppException(RECONCILIATION_REQUIRED));

        FinancialFacts facts = financialFacts(payment, false);
        Instant now = applicationClock.instant();

        BaseErrorCode cancelError = eligibility.cancel(booking, now);
        BaseErrorCode admissionError = eligibility.admission(booking, now);
        BaseErrorCode financeError = evaluateContextFinancialError(booking, payment, facts, cancelError);

        if (cancelError == null) {
            cancelError = financeError;
            if (cancelError == null && booking.getStatus() == BookingStatus.CONFIRMED
                    && payment.getStatus() != PaymentStatus.PAID) {
                cancelError = RECONCILIATION_REQUIRED;
            }
        }
        if (admissionError == null) {
            admissionError = financeError;
        }
        if (decisions.existsByBookingIdAndActorKind(bookingId, "OWNER")) {
            cancelError = admissionError = RESPONSIBILITY_ALREADY_ACCEPTED;
        }

        var state = effectiveState.evaluate(booking, now);
        Long payableAmount = resolvePayableAmount(booking, payment, financeError);

        return new ServiceFailureContextResponse(
                bookingId,
                booking.getVersion(),
                decisions.version(bookingId),
                state.effectiveStatus(),
                booking.getStatus(),
                state.stateReconciliationPending(),
                now,
                payableAmount,
                "VND",
                publicDecisions(bookingId),
                summary(facts.refund()),
                availability(cancelError),
                availability(admissionError)
        );
    }

    // =========================================================================
    // Core Command Execution Pipeline
    // =========================================================================

    private ServiceFailureCommandResponse execute(ServiceFailureExecutionRequest req) {
        Objects.requireNonNull(req.idempotencyKey());

        // 1. Initial actor profile & route authorization
        UserProfile actor = currentProfileProvider.requireProfile();
        requireActive(actor);

        var route = bookings.findServiceFailureRoute(req.bookingId())
                .orElseThrow(BookingServiceFailureService::notFound);
        if (!Objects.equals(route.getOwnerId(), actor.getId())) {
            throw notFound();
        }

        // 2. Canonical payload hashing for idempotency
        String hash = BookingCommandPayloadHasher.sha256(canonical(
                req.operation(),
                req.bookingId(),
                req.expectedVersion(),
                req.expectedDecisionVersion(),
                req.affectedAt(),
                req.reason()
        ));

        // 3. Fast replay check prior to acquiring database locks
        if (commands.findReplay(actor.getId(), req.operation(), req.idempotencyKey(), hash).isPresent()) {
            return replay(actor.getId(), req.operation(), req.idempotencyKey());
        }

        // 4. Pessimistic locking in strict hierarchical order:
        //    Actor Profile -> Connector -> Booking -> Payment
        actor = lockActorProfile(actor.getId());

        // Double-check replay under actor profile lock
        if (commands.findReplay(actor.getId(), req.operation(), req.idempotencyKey(), hash).isPresent()) {
            return replay(actor.getId(), req.operation(), req.idempotencyKey());
        }

        connectors.findByIdWithLock(route.getConnectorId())
                .orElseThrow(BookingServiceFailureService::notFound);

        Booking booking = bookings.findByIdWithLock(req.bookingId())
                .orElseThrow(BookingServiceFailureService::notFound);
        verifyBookingOwnership(booking, actor.getId());

        Payment payment = payments.findByBookingIdWithLock(req.bookingId())
                .orElseThrow(() -> new AppException(RECONCILIATION_REQUIRED));

        // 5. Gather financial facts & timing
        boolean unpaid = req.isCancel() && booking.getStatus() == BookingStatus.PENDING;
        FinancialFacts facts = financialFacts(payment, true);
        Instant now = applicationClock.instant();

        // 6. Version & decision conflicts
        long decisionVersion = validateVersionsAndDecisions(
                req.bookingId(),
                booking,
                req.expectedVersion(),
                req.expectedDecisionVersion(),
                req.isCancel()
        );

        // 7. Policy eligibility & affected time validation
        validateEligibilityAndTimes(booking, req, now);
        validateFinancialState(booking, payment, facts, unpaid, req.isCancel());

        // 8. Capture the validated input state before any lifecycle mutation.
        Map<String, Object> evidence = evidence(booking, payment, facts, now);

        // 9. Reconcile no-show if needed (for Station Failure Acceptance on confirmed booking)
        if (!req.isCancel() && booking.getStatus() == BookingStatus.CONFIRMED) {
            if (!noShow.reconcileLocked(booking, payment, now)) {
                throw new AppException(RECONCILIATION_REQUIRED);
            }
        }

        // 10. Record command & state transition
        BookingCommand command = commands.recordSuccess(actor, req.operation(), req.idempotencyKey(), hash, booking, now);
        if (req.isCancel()) {
            history.recordUserTransition(
                    command,
                    BookingStatusActorType.OWNER,
                    BookingStatusReason.OWNER_CANCELLED,
                    now,
                    current -> current.cancel("STATION_FAILURE", now)
            );
        }

        // 11. Create or resolve refund obligation
        UUID decisionId = UUID.randomUUID();
        Refund refund = resolveRefund(actor, booking, payment, facts, req, command.getId(), decisionId, unpaid, now);

        // 12. Persist decision & return response
        Instant affectedAt = req.isCancel() ? now : req.affectedAt();
        BookingServiceFailureDecision decision = decisions.saveAndFlush(BookingServiceFailureDecision.owner(
                decisionId,
                command,
                payment,
                decisionVersion + 1,
                req.decisionKind(),
                req.reason(),
                affectedAt,
                now,
                refund,
                evidence
        ));

        if (req.isCancel()) notificationOutbox.recordStationCancelled(command);
        eventOutbox.recordDecision(decision);
        bookings.flush();
        return response(booking, decision, refund);
    }

    // =========================================================================
    // Concurrency, Authorization & Validation Helpers
    // =========================================================================

    private UserProfile lockActorProfile(UUID actorId) {
        UserProfile lockedActor = profiles.findByIdWithLock(actorId)
                .orElseThrow(BookingServiceFailureService::notFound);
        requireActive(lockedActor);
        return lockedActor;
    }

    private void verifyBookingOwnership(Booking booking, UUID actorId) {
        UUID ownerId = booking.getConnector().getChargePoint().getStation().getOwner().getId();
        if (!Objects.equals(ownerId, actorId)) {
            throw notFound();
        }
    }

    private long validateVersionsAndDecisions(
            UUID bookingId,
            Booking booking,
            Long expectedBookingVersion,
            Long expectedDecisionVersion,
            boolean isCancel) {
        if (!Objects.equals(booking.getVersion(), expectedBookingVersion)) {
            throw AppException.withDetails(VERSION_CONFLICT, Map.of("bookingVersion", booking.getVersion()));
        }

        long currentDecisionVersion = decisions.version(bookingId);
        if (!isCancel && currentDecisionVersion != expectedDecisionVersion) {
            throw AppException.withDetails(DECISION_CHANGED, Map.of("decisionVersion", currentDecisionVersion));
        }

        if (decisions.existsByBookingIdAndActorKind(bookingId, "OWNER")) {
            throw new AppException(RESPONSIBILITY_ALREADY_ACCEPTED);
        }

        return currentDecisionVersion;
    }

    private void validateEligibilityAndTimes(
            Booking booking,
            ServiceFailureExecutionRequest req,
            Instant now) {
        BaseErrorCode eligibilityError = req.isCancel()
                ? eligibility.cancel(booking, now)
                : eligibility.admission(booking, now);
        requireEligible(eligibilityError);

        if (!req.isCancel()) {
            Instant affectedAt = req.affectedAt();
            boolean invalidAffectedTime = affectedAt.isAfter(now)
                    || booking.getEndAt() == null
                    || affectedAt.isAfter(booking.getEndAt());
            if (invalidAffectedTime) {
                throw new AppException(CommonErrorCode.INVALID_REQUEST);
            }
        }
    }

    private void validateFinancialState(
            Booking booking,
            Payment payment,
            FinancialFacts facts,
            boolean isUnpaid,
            boolean isCancel) {
        if (isUnpaid) {
            boolean invalidPaymentStatus = payment.getStatus() != PaymentStatus.PENDING
                    && payment.getStatus() != PaymentStatus.FAILED;
            boolean hasAppliedReceipts = !facts.appliedIds().isEmpty();
            boolean hasRefund = facts.refund() != null;

            if (invalidPaymentStatus || hasAppliedReceipts || hasRefund) {
                throw new AppException(RECONCILIATION_REQUIRED);
            }
        } else {
            if (facts.appliedIds().size() != 1) {
                throw new AppException(RECONCILIATION_REQUIRED);
            }
            requireEligible(eligibility.financial(booking, payment, facts.source(), facts.refund()));
            if (isCancel && payment.getStatus() != PaymentStatus.PAID) {
                throw new AppException(RECONCILIATION_REQUIRED);
            }
        }
    }

    private BaseErrorCode evaluateContextFinancialError(
            Booking booking,
            Payment payment,
            FinancialFacts facts,
            BaseErrorCode cancelError) {
        BaseErrorCode financeError = facts.appliedIds().size() == 1
                ? eligibility.financial(booking, payment, facts.source(), facts.refund())
                : RECONCILIATION_REQUIRED;

        if (booking.getStatus() == BookingStatus.PENDING) {
            boolean invalidPendingState = (payment.getStatus() != PaymentStatus.PENDING
                    && payment.getStatus() != PaymentStatus.FAILED)
                    || !facts.appliedIds().isEmpty()
                    || facts.refund() != null;
            if (invalidPendingState) {
                financeError = RECONCILIATION_REQUIRED;
            } else if (cancelError == null) {
                financeError = null;
            }
        }
        return financeError;
    }

    private Long resolvePayableAmount(Booking booking, Payment payment, BaseErrorCode financeError) {
        if (financeError != null) {
            return null;
        }
        return booking.getStatus() == BookingStatus.PENDING ? 0L : payment.getAmount().longValueExact();
    }

    private static void validateRequestVersionAndReason(Long version, String reason) {
        if (version == null || version < 0 || reason == null || reason.length() < 5 || reason.length() > 2000) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    private static void requireEligible(BaseErrorCode error) {
        if (error != null) {
            throw new AppException(error);
        }
    }

    private static void requireActive(UserProfile actor) {
        if (actor.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ProfileErrorCode.PROFILE_NOT_FOUND);
        }
    }

    private static AppException notFound() {
        return new AppException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    // =========================================================================
    // Financial & Refund Helpers
    // =========================================================================

    private FinancialFacts financialFacts(Payment payment, boolean lock) {
        List<UUID> appliedReceiptIds = receipts.findAppliedReceiptIdsByPaymentId(payment.getId());
        PaymentTransaction source = null;
        if (appliedReceiptIds.size() == 1) {
            UUID sourceReceiptId = appliedReceiptIds.getFirst();
            source = (lock ? receipts.findByIdWithLock(sourceReceiptId) : receipts.findById(sourceReceiptId))
                    .orElseThrow(() -> new AppException(RECONCILIATION_REQUIRED));
        }

        List<UUID> refundIds = refunds.findIdsByPaymentId(payment.getId());
        if (refundIds.size() > 1) {
            throw new AppException(RECONCILIATION_REQUIRED);
        }

        Refund refund = null;
        if (!refundIds.isEmpty()) {
            UUID refundId = refundIds.getFirst();
            refund = (lock ? refunds.findByIdWithLock(refundId) : refunds.findById(refundId))
                    .orElseThrow(() -> new AppException(RECONCILIATION_REQUIRED));
        }

        return new FinancialFacts(appliedReceiptIds, source, refund);
    }

    private Refund resolveRefund(
            UserProfile actor,
            Booking booking,
            Payment payment,
            FinancialFacts facts,
            ServiceFailureExecutionRequest req,
            UUID commandId,
            UUID decisionId,
            boolean isUnpaid,
            Instant now) {
        Refund refund = facts.refund();
        if (!isUnpaid && refund == null) {
            UUID basisId = req.isCancel() ? commandId : decisionId;
            refund = obligations.createObligation(new CreateRefundObligationCommand(
                    booking,
                    payment,
                    facts.source().getId(),
                    req.refundBasisType(),
                    basisId,
                    RefundReason.STATION_FAILURE,
                    actor,
                    now
            ));
        }
        return refund;
    }

    // =========================================================================
    // Idempotency, Replay & Response Helpers
    // =========================================================================

    private ServiceFailureCommandResponse replay(UUID actorId, BookingCommandOperation operation, UUID key) {
        BookingCommand command = commandRepository.findByActor_IdAndOperationAndRequestKey(actorId, operation, key)
                .orElseThrow();
        BookingServiceFailureDecision decision = decisions.findByCommandId(command.getId())
                .orElseThrow(() -> new AppException(RECONCILIATION_REQUIRED));

        Booking booking = command.getBooking();
        verifyBookingOwnership(booking, actorId);

        List<UUID> refundIds = refunds.findIdsByPaymentId(decision.getPayment().getId());
        if (refundIds.size() > 1) {
            throw new AppException(RECONCILIATION_REQUIRED);
        }

        Refund refund = refundIds.isEmpty()
                ? null
                : refunds.findById(refundIds.getFirst()).orElseThrow();

        return response(booking, decision, refund);
    }

    private ServiceFailureCommandResponse response(
            Booking booking,
            BookingServiceFailureDecision decision,
            Refund refund) {
        var state = effectiveState.evaluate(booking, applicationClock.instant());
        BookingCancellationReason cancellationReason = state.cancellationReason() == null
                ? null
                : BookingCancellationReason.valueOf(state.cancellationReason().name());

        return new ServiceFailureCommandResponse(
                booking.getId(),
                booking.getVersion(),
                state.effectiveStatus(),
                cancellationReason,
                state.stateReconciliationPending(),
                decisions.version(booking.getId()),
                ServiceFailureDecisionResponse.from(decision),
                summary(refund)
        );
    }

    private List<ServiceFailureDecisionResponse> publicDecisions(UUID bookingId) {
        return decisions.findByBookingIdOrderBySequenceNoAsc(bookingId)
                .stream()
                .map(ServiceFailureDecisionResponse::from)
                .toList();
    }

    private ServiceFailureContextResponse.RefundSummary summary(Refund refund) {
        if (refund == null) {
            return null;
        }
        return new ServiceFailureContextResponse.RefundSummary(
                refund.getId(),
                refund.getAmount().longValueExact(),
                refund.getCurrency(),
                refund.getStatus(),
                refund.isRequiresOwnerAction()
        );
    }

    private ServiceFailureContextResponse.Availability availability(BaseErrorCode error) {
        return new ServiceFailureContextResponse.Availability(
                error == null,
                error == null ? null : error.getCode()
        );
    }

    private static String canonical(Object... fields) {
        StringBuilder out = new StringBuilder("service-failure-v1\n");
        for (Object field : fields) {
            String value = field == null ? "" : field.toString();
            int length = field == null ? -1 : value.length();
            out.append(length).append(':').append(value).append('\n');
        }
        return out.toString();
    }

    private Map<String, Object> evidence(Booking booking, Payment payment, FinancialFacts facts, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incidents", incidentEvidence.forBooking(booking.getId()));
        data.put("persistedStatus", booking.getStatus().name());
        data.put("bookingVersion", booking.getVersion());
        data.put("effectiveStatus", effectiveState.evaluate(booking, now).effectiveStatus().name());
        data.put("evaluatedAt", now.toString());
        data.put("paymentStatus", payment.getStatus().name());
        data.put("amount", payment.getAmount().toPlainString());
        data.put("currency", payment.getCurrency());
        data.put("environment", payment.getEnvironment().name());
        data.put("needsReconciliation", payment.isNeedsReconciliation());

        putTimeIfPresent(data, "startAt", booking.getStartAt());
        putTimeIfPresent(data, "endAt", booking.getEndAt());
        putTimeIfPresent(data, "checkInDeadline", booking.getCheckInDeadline());
        putTimeIfPresent(data, "expiresAt", booking.getExpiresAt());

        if (booking.getCancellationReason() != null) {
            data.put("cancellationReason", booking.getCancellationReason());
        }
        if (facts.source() != null) {
            data.put("receiptId", facts.source().getId().toString());
            data.put("receiptClassification", "APPLIED");
            data.put("receiptAmount", facts.source().getAmount().toPlainString());
        }
        if (facts.refund() != null) {
            data.put("existingRefundId", facts.refund().getId().toString());
        }
        data.put("policyVersion", "v1");
        return data;
    }

    private static void putTimeIfPresent(Map<String, Object> data, String key, Instant value) {
        if (value != null) {
            data.put(key, value.toString());
        }
    }

    // =========================================================================
    // Internal Records
    // =========================================================================

    private record FinancialFacts(
            List<UUID> appliedIds,
            PaymentTransaction source,
            Refund refund
    ) {}

    private record ServiceFailureExecutionRequest(
            UUID bookingId,
            UUID idempotencyKey,
            Long expectedVersion,
            Long expectedDecisionVersion,
            Instant affectedAt,
            String reason,
            boolean isCancel
    ) {
        BookingCommandOperation operation() {
            return isCancel
                    ? BookingCommandOperation.OWNER_CANCEL_BOOKING
                    : BookingCommandOperation.OWNER_ACCEPT_STATION_FAILURE;
        }

        ServiceFailureDecisionKind decisionKind() {
            return isCancel
                    ? ServiceFailureDecisionKind.OWNER_CANCEL_BOOKING
                    : ServiceFailureDecisionKind.OWNER_ACCEPT_STATION_FAILURE;
        }

        RefundBasisType refundBasisType() {
            return isCancel
                    ? RefundBasisType.BOOKING_CANCELLATION
                    : RefundBasisType.SERVICE_FAILURE_DECISION;
        }
    }
}
