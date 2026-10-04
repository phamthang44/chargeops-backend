package com.thang.chargeops.support.service;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandPayloadHasher;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.command.BookingCommandRepository;
import com.thang.chargeops.booking.decision.BookingServiceFailureDecision;
import com.thang.chargeops.booking.decision.BookingServiceFailureDecisionRepository;
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
import com.thang.chargeops.booking.service.ServiceFailureDecisionReader;
import com.thang.chargeops.booking.service.impl.BookingNoShowServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BaseErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.RefundPolicyErrorCode;
import com.thang.chargeops.exception.errorcode.ServiceFailureErrorCode;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.model.CreateRefundObligationCommand;
import com.thang.chargeops.refund.model.RefundBasisType;
import com.thang.chargeops.refund.model.RefundReason;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.RefundObligationService;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.support.dto.request.ReviewRefundPolicyRequest;
import com.thang.chargeops.support.dto.response.RefundPolicyContextResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketEscalation;
import com.thang.chargeops.support.model.RefundPolicyOutcome;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketEscalationRepository;
import com.thang.chargeops.support.repository.TicketFindingRepository;
import com.thang.chargeops.support.repository.TicketMessageRepository;
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

/** Financial policy for one escalated case; support workflow remains independent. */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRefundPolicyService {
    private static final BookingCommandOperation OPERATION = BookingCommandOperation.ADMIN_REVIEW_STATION_FAILURE;

    private final CurrentProfileProvider currentProfile;
    private final UserProfileRepository profiles;
    private final ConnectorRepository connectors;
    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final PaymentTransactionRepository receipts;
    private final RefundRepository refunds;
    private final SupportTicketRepository tickets;
    private final TicketEscalationRepository escalations;
    private final TicketFindingRepository findings;
    private final TicketMessageRepository messages;
    private final BookingCommandRegistry commands;
    private final BookingCommandRepository commandRepository;
    private final BookingServiceFailureDecisionRepository decisions;
    private final ServiceFailureDecisionReader decisionReader;
    private final ServiceFailureEligibilityPolicy eligibility;
    private final BookingEffectiveStatePolicy effectiveState;
    private final BookingStatusHistoryRecorder history;
    private final BookingNoShowServiceImpl noShow;
    private final RefundObligationService obligations;
    private final Clock clock;

    @Transactional
    public ServiceFailureCommandResponse review(UUID ticketId, UUID escalationId, UUID key,
                                                ReviewRefundPolicyRequest request) {
        validateRequest(key, request);
        var route = requireRoute(ticketId, escalationId);
        UserProfile admin = requireActive(currentProfile.requireProfile());
        admin = requireActive(profiles.findByIdWithLock(admin.getId()).orElseThrow(AdminRefundPolicyService::notFound));
        String hash = payloadHash(ticketId, escalationId, route.getBookingId(), request);
        if (commands.findReplay(admin.getId(), OPERATION, key, hash).isPresent()) {
            return replay(admin.getId(), key, ticketId, escalationId);
        }

        // Shared financial lock order: actor -> connector -> booking -> payment -> receipt -> case -> refund.
        connectors.findByIdWithLock(route.getConnectorId()).orElseThrow(AdminRefundPolicyService::notFound);
        Booking booking = bookings.findByIdWithLock(route.getBookingId()).orElseThrow(AdminRefundPolicyService::notFound);
        Payment payment = payments.findByBookingIdWithLock(booking.getId())
                .orElseThrow(() -> new AppException(ServiceFailureErrorCode.RECONCILIATION_REQUIRED));
        List<UUID> appliedIds = receipts.findAppliedReceiptIdsByPaymentId(payment.getId());
        PaymentTransaction source = readSource(appliedIds, true);
        SupportTicket ticket = tickets.findByIdForUpdate(ticketId).orElseThrow(AdminRefundPolicyService::notFound);
        TicketEscalation escalation = escalations.findByIdWithLock(escalationId).orElseThrow(AdminRefundPolicyService::notFound);
        requireCaseRelation(ticket, escalation, booking);
        requireEligible(caseError(ticket, escalation));
        Refund existingRefund = readRefund(payment, true);
        long decisionVersion = validateVersions(ticket, booking, request);
        Instant now = clock.instant();
        boolean grant = request.outcome() == RefundPolicyOutcome.GRANT_FULL_REFUND;
        if (grant) {
            requireEligible(grantError(booking, payment, source, appliedIds, existingRefund, now));
            validateAffectedAt(booking, request.affectedAt(), now);
        } else if (existingRefund != null) {
            throw new AppException(RefundPolicyErrorCode.REFUND_ALREADY_GRANTED);
        }

        Map<String, Object> evidence = evidence(ticket, escalation, booking, payment, source, existingRefund, now);
        var command = commands.recordSuccess(admin, OPERATION, key, hash, booking, now);
        UUID decisionId = UUID.randomUUID();
        Refund refund = null;
        if (grant) {
            reconcileBooking(booking, payment, command, now);
            refund = existingRefund == null ? obligations.createObligation(new CreateRefundObligationCommand(
                    booking, payment, source.getId(), RefundBasisType.SERVICE_FAILURE_DECISION,
                    decisionId, RefundReason.STATION_FAILURE, admin, now)) : existingRefund;
        }
        var decision = decisions.saveAndFlush(BookingServiceFailureDecision.admin(decisionId, command, payment,
                decisionVersion + 1, ticketId, escalationId, request.reason(), request.affectedAt(), now, refund, evidence));
        bookings.flush();
        return response(booking, decision, refund);
    }

    @Transactional(readOnly = true)
    public RefundPolicyContextResponse context(UUID ticketId, UUID escalationId) {
        requireActive(currentProfile.requireProfile());
        var route = requireRoute(ticketId, escalationId);
        Booking booking = bookings.findById(route.getBookingId()).orElseThrow(AdminRefundPolicyService::notFound);
        SupportTicket ticket = tickets.findById(ticketId).orElseThrow(AdminRefundPolicyService::notFound);
        TicketEscalation escalation = escalations.findById(escalationId).orElseThrow(AdminRefundPolicyService::notFound);
        requireCaseRelation(ticket, escalation, booking);
        Payment payment = payments.findByBookingId(booking.getId()).orElse(null);
        List<UUID> appliedIds = payment == null ? List.of() : receipts.findAppliedReceiptIdsByPaymentId(payment.getId());
        PaymentTransaction source = readSource(appliedIds, false);
        Refund refund = payment == null ? null : readRefund(payment, false);
        Instant now = clock.instant();
        BaseErrorCode caseError = caseError(ticket, escalation);
        BaseErrorCode grantError = caseError == null
                ? grantError(booking, payment, source, appliedIds, refund, now) : caseError;
        BaseErrorCode insufficientError = caseError;
        if (insufficientError == null && payment == null) {
            insufficientError = ServiceFailureErrorCode.RECONCILIATION_REQUIRED;
        }
        if (insufficientError == null && refund != null) {
            insufficientError = RefundPolicyErrorCode.REFUND_ALREADY_GRANTED;
        }
        var state = effectiveState.evaluate(booking, now);
        var financial = decisionReader.forTicket(booking.getId(), ticketId, false);
        var ownerAction = availability(TicketErrorCode.ACCESS_DENIED);
        var bookingContext = new ServiceFailureContextResponse(booking.getId(), booking.getVersion(),
                financial.decisionVersion(), state.effectiveStatus(), booking.getStatus(),
                state.stateReconciliationPending(), now, grantError == null ? payment.getAmount().longValueExact() : null,
                "VND", financial.serviceFailureDecisions(), financial.refund(), ownerAction, ownerAction);
        return new RefundPolicyContextResponse(ticketId, escalationId, ticket.getVersion(), caseError == null,
                bookingContext, availability(grantError), availability(insufficientError));
    }

    private SupportTicketRepository.RefundPolicyRoute requireRoute(UUID ticketId, UUID escalationId) {
        return tickets.findRefundPolicyRoute(ticketId, escalationId).orElseThrow(AdminRefundPolicyService::notFound);
    }

    private static void requireCaseRelation(SupportTicket ticket, TicketEscalation escalation, Booking booking) {
        if (!Objects.equals(escalation.getTicket().getId(), ticket.getId()) || ticket.getBooking() == null
                || !Objects.equals(ticket.getBooking().getId(), booking.getId()) || ticket.getStation() == null
                || !Objects.equals(ticket.getStation().getId(), booking.getConnector().getChargePoint().getStation().getId())) {
            throw notFound();
        }
    }

    private static BaseErrorCode caseError(SupportTicket ticket, TicketEscalation escalation) {
        if (escalation.getResolvedAt() != null) return TicketErrorCode.ESCALATION_NOT_ACTIVE;
        if (ticket.getStatus() != TicketStatus.OPEN && ticket.getStatus() != TicketStatus.IN_PROGRESS)
            return TicketErrorCode.STATE_CONFLICT;
        return null;
    }

    private long validateVersions(SupportTicket ticket, Booking booking, ReviewRefundPolicyRequest request) {
        if (!Objects.equals(ticket.getVersion(), request.expectedVersion())) {
            throw AppException.withDetails(TicketErrorCode.VERSION_CONFLICT, Map.of("ticketVersion", ticket.getVersion()));
        }
        if (!Objects.equals(booking.getVersion(), request.expectedBookingVersion())) {
            throw AppException.withDetails(ServiceFailureErrorCode.VERSION_CONFLICT, Map.of("bookingVersion", booking.getVersion()));
        }
        long version = decisions.version(booking.getId());
        if (version != request.expectedDecisionVersion()) {
            throw AppException.withDetails(ServiceFailureErrorCode.DECISION_CHANGED, Map.of("decisionVersion", version));
        }
        return version;
    }

    private BaseErrorCode grantError(Booking booking, Payment payment, PaymentTransaction source,
                                   List<UUID> appliedIds, Refund refund, Instant now) {
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            if (booking.getCheckInDeadline() == null || booking.getCheckedInAt() != null || booking.getChargingStartedAt() != null)
                return ServiceFailureErrorCode.RECONCILIATION_REQUIRED;
            if (payment != null && payment.getStatus() != PaymentStatus.PAID)
                return ServiceFailureErrorCode.RECONCILIATION_REQUIRED;
        } else {
            BaseErrorCode lifecycleError = eligibility.admission(booking, now);
            if (lifecycleError != null) return lifecycleError;
        }
        if (payment == null || appliedIds.size() != 1) return ServiceFailureErrorCode.RECONCILIATION_REQUIRED;
        return eligibility.financial(booking, payment, source, refund);
    }

    private void reconcileBooking(Booking booking, Payment payment, BookingCommand command, Instant now) {
        if (booking.getStatus() != BookingStatus.CONFIRMED) return;
        if (now.isBefore(booking.getCheckInDeadline())) {
            history.recordUserTransition(command, BookingStatusActorType.ADMIN, BookingStatusReason.ADMIN_POLICY_CANCELLED,
                    now, current -> current.cancel("STATION_FAILURE", now));
        } else if (!noShow.reconcileLocked(booking, payment, now)) {
            throw new AppException(ServiceFailureErrorCode.RECONCILIATION_REQUIRED);
        }
    }

    private PaymentTransaction readSource(List<UUID> appliedIds, boolean lock) {
        if (appliedIds.size() != 1) return null;
        UUID id = appliedIds.getFirst();
        return (lock ? receipts.findByIdWithLock(id) : receipts.findById(id))
                .orElseThrow(() -> new AppException(ServiceFailureErrorCode.RECONCILIATION_REQUIRED));
    }

    private Refund readRefund(Payment payment, boolean lock) {
        List<UUID> ids = refunds.findIdsByPaymentId(payment.getId());
        if (ids.size() > 1) throw new AppException(ServiceFailureErrorCode.RECONCILIATION_REQUIRED);
        if (ids.isEmpty()) return null;
        return (lock ? refunds.findByIdWithLock(ids.getFirst()) : refunds.findById(ids.getFirst()))
                .orElseThrow(() -> new AppException(ServiceFailureErrorCode.RECONCILIATION_REQUIRED));
    }

    private ServiceFailureCommandResponse replay(UUID actorId, UUID key, UUID ticketId, UUID escalationId) {
        var command = commandRepository.findByActor_IdAndOperationAndRequestKey(actorId, OPERATION, key).orElseThrow();
        var decision = decisions.findByCommandId(command.getId())
                .orElseThrow(() -> new AppException(ServiceFailureErrorCode.RECONCILIATION_REQUIRED));
        if (!ticketId.equals(decision.getTicketId()) || !escalationId.equals(decision.getEscalationId())) throw notFound();
        return response(command.getBooking(), decision, readRefund(decision.getPayment(), false));
    }

    private ServiceFailureCommandResponse response(Booking booking, BookingServiceFailureDecision decision, Refund refund) {
        var state = effectiveState.evaluate(booking, clock.instant());
        var reason = state.cancellationReason() == null ? null : BookingCancellationReason.valueOf(state.cancellationReason().name());
        var summary = refund == null ? null : new ServiceFailureContextResponse.RefundSummary(refund.getId(),
                refund.getAmount().longValueExact(), refund.getCurrency(), refund.getStatus(), null);
        return new ServiceFailureCommandResponse(booking.getId(), booking.getVersion(), state.effectiveStatus(), reason,
                state.stateReconciliationPending(), decisions.version(booking.getId()), ServiceFailureDecisionResponse.from(decision), summary);
    }

    private Map<String, Object> evidence(SupportTicket ticket, TicketEscalation escalation, Booking booking,
                                       Payment payment, PaymentTransaction source, Refund refund, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketVersion", ticket.getVersion());
        data.put("escalationRequestedAt", escalation.getRequestedAt().toString());
        data.put("findingIds", findings.findByTicketIdOrderByRecordedAtAscIdAsc(ticket.getId()).stream().map(f -> f.getId().toString()).toList());
        data.put("messageIds", messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId()).stream().map(m -> m.getId().toString()).toList());
        data.put("persistedStatus", booking.getStatus().name());
        data.put("bookingVersion", booking.getVersion());
        data.put("effectiveStatus", effectiveState.evaluate(booking, now).effectiveStatus().name());
        data.put("evaluatedAt", now.toString());
        data.put("paymentStatus", payment.getStatus().name());
        data.put("amount", payment.getAmount().toPlainString());
        data.put("currency", payment.getCurrency());
        data.put("environment", payment.getEnvironment().name());
        data.put("needsReconciliation", payment.isNeedsReconciliation());
        if (booking.getCancellationReason() != null) data.put("cancellationReason", booking.getCancellationReason());
        if (booking.getStartAt() != null) data.put("startAt", booking.getStartAt().toString());
        if (booking.getEndAt() != null) data.put("endAt", booking.getEndAt().toString());
        if (booking.getCheckInDeadline() != null) data.put("checkInDeadline", booking.getCheckInDeadline().toString());
        if (source != null) {
            data.put("receiptId", source.getId().toString());
            data.put("receiptClassification", source.getApplicationClassification().name());
            data.put("receiptAmount", source.getAmount().toPlainString());
        }
        if (refund != null) data.put("existingRefundId", refund.getId().toString());
        data.put("policyVersion", "v1");
        return data;
    }

    private static void validateRequest(UUID key, ReviewRefundPolicyRequest request) {
        if (key == null || request == null || request.expectedVersion() == null || request.expectedVersion() < 0
                || request.expectedBookingVersion() == null || request.expectedBookingVersion() < 0
                || request.expectedDecisionVersion() == null || request.expectedDecisionVersion() < 0
                || request.outcome() == null || request.reason() == null || request.reason().length() < 5 || request.reason().length() > 2000
                || (request.outcome() == RefundPolicyOutcome.GRANT_FULL_REFUND) != (request.affectedAt() != null)) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    private static void validateAffectedAt(Booking booking, Instant affectedAt, Instant now) {
        if (affectedAt.isAfter(now) || booking.getEndAt() == null || affectedAt.isAfter(booking.getEndAt()))
            throw new AppException(CommonErrorCode.INVALID_REQUEST);
    }

    private static UserProfile requireActive(UserProfile actor) {
        if (actor.getStatus() != UserStatus.ACTIVE) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return actor;
    }

    private static void requireEligible(BaseErrorCode error) {
        if (error != null) throw new AppException(error);
    }

    private static ServiceFailureContextResponse.Availability availability(BaseErrorCode error) {
        return new ServiceFailureContextResponse.Availability(error == null, error == null ? null : error.getCode());
    }

    private static AppException notFound() {
        return new AppException(TicketErrorCode.NOT_FOUND);
    }

    private static String payloadHash(UUID ticketId, UUID escalationId, UUID bookingId, ReviewRefundPolicyRequest request) {
        StringBuilder canonical = new StringBuilder("admin-refund-policy-v1\n");
        Object[] fields = {ticketId, escalationId, bookingId, request.expectedVersion(), request.expectedBookingVersion(),
                request.expectedDecisionVersion(), request.outcome(), request.affectedAt(), request.reason()};
        for (Object field : fields) {
            String value = field == null ? "" : field.toString();
            canonical.append(field == null ? -1 : value.length()).append(':').append(value).append('\n');
        }
        return BookingCommandPayloadHasher.sha256(canonical.toString());
    }
}
