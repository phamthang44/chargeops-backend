package com.thang.chargeops.booking.decision;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.refund.entity.Refund;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Immutable business decision, independent of the mutable refund execution state. */
@Entity @Immutable @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "booking_service_failure_decisions")
public class BookingServiceFailureDecision {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "booking_id") private Booking booking;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "payment_id") private Payment payment;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "command_id") private BookingCommand command;
    @Column(name = "sequence_no", nullable = false) private long sequenceNo;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50) private ServiceFailureDecisionKind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private ServiceFailureDecisionResult result;
    @Column(name = "actor_profile_id", nullable = false) private UUID actorProfileId;
    @Column(name = "actor_kind", nullable = false, length = 20) private String actorKind;
    @Column(name = "actor_display_name_snapshot", nullable = false) private String actorDisplayNameSnapshot;
    @Column(name = "station_id", nullable = false) private UUID stationId;
    @Column(name = "owner_profile_id_snapshot", nullable = false) private UUID ownerProfileIdSnapshot;
    @Column(nullable = false, length = 2000) private String reason;
    @Column(name = "affected_at") private Instant affectedAt;
    @Column(name = "decided_at", nullable = false) private Instant decidedAt;
    @Column(name = "policy_code", nullable = false, length = 100) private String policyCode;
    @Column(name = "policy_version", nullable = false, length = 100) private String policyVersion;
    @Column(name = "ticket_id") private UUID ticketId;
    @Column(name = "escalation_id") private UUID escalationId;
    @Column(name = "source_receipt_id") private UUID sourceReceiptId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "refund_id") private Refund refund;
    @Column(name = "amount_vnd", nullable = false, precision = 19, scale = 2) private BigDecimal amountVnd;
    @Column(nullable = false, length = 3) private String currency;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evidenceSnapshot;

    public static BookingServiceFailureDecision admin(UUID id, BookingCommand command, Payment payment, long sequence,
            UUID ticketId, UUID escalationId, String reason, Instant affectedAt, Instant decidedAt,
            Refund refund, Map<String, Object> evidence) {
        BookingServiceFailureDecision decision = create(id, command, payment, sequence,
                reason, affectedAt, decidedAt, refund, evidence);
        decision.kind = ServiceFailureDecisionKind.ADMIN_REVIEW_STATION_FAILURE;
        decision.actorKind = "ADMIN";
        String name = command.getActor().getDisplayName();
        decision.actorDisplayNameSnapshot = name == null || name.isBlank() ? "Quản trị viên" : name;
        decision.ticketId = ticketId;
        decision.escalationId = escalationId;
        decision.result = refund == null ? ServiceFailureDecisionResult.INSUFFICIENT_EVIDENCE
                : ServiceFailureDecisionResult.FULL_REFUND;
        return decision;
    }

    public static BookingServiceFailureDecision owner(UUID id, BookingCommand command, Payment payment, long sequence,
            ServiceFailureDecisionKind kind, String reason, Instant affectedAt, Instant decidedAt,
            Refund refund, Map<String, Object> evidence) {
        BookingServiceFailureDecision decision = create(id, command, payment, sequence,
                reason, affectedAt, decidedAt, refund, evidence);
        decision.kind = kind;
        decision.result = refund == null ? ServiceFailureDecisionResult.NO_APPLIED_PAYMENT : ServiceFailureDecisionResult.FULL_REFUND;
        decision.actorKind = "OWNER";
        String name = command.getActor().getDisplayName();
        decision.actorDisplayNameSnapshot = name == null || name.isBlank() ? "Chủ trạm" : name;
        return decision;
    }

    private static BookingServiceFailureDecision create(UUID id, BookingCommand command, Payment payment, long sequence,
            String reason, Instant affectedAt, Instant decidedAt, Refund refund, Map<String, Object> evidence) {
        BookingServiceFailureDecision decision = new BookingServiceFailureDecision();
        decision.id = id;
        decision.booking = command.getBooking();
        decision.payment = payment;
        decision.command = command;
        decision.sequenceNo = sequence;
        decision.actorProfileId = command.getActor().getId();
        var station = decision.booking.getConnector().getChargePoint().getStation();
        decision.stationId = station.getId();
        decision.ownerProfileIdSnapshot = station.getOwner().getId();
        decision.reason = reason;
        decision.affectedAt = affectedAt;
        decision.decidedAt = decidedAt;
        decision.policyCode = "STATION_FAILURE_FULL_PACKAGE";
        decision.policyVersion = "v1";
        decision.refund = refund;
        decision.sourceReceiptId = refund == null ? null : refund.getSourcePaymentTransaction().getId();
        decision.amountVnd = refund == null ? BigDecimal.ZERO : refund.getAmount();
        decision.currency = "VND";
        decision.evidenceSnapshot = Map.copyOf(evidence);
        return decision;
    }
}
