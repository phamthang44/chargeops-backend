package com.thang.chargeops.support.entity;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.support.model.TicketFindingConclusion;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "ticket_findings", indexes = {
        @Index(name = "idx_ticket_findings_ticket_recorded", columnList = "ticket_id, recorded_at, id"),
        @Index(name = "idx_ticket_findings_booking_conclusion", columnList = "booking_id, conclusion, recorded_at, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketFinding extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private SupportTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    private Booking booking;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private TicketFindingConclusion conclusion;

    @Column(name = "affected_at", nullable = false, updatable = false)
    private Instant affectedAt;

    @Column(nullable = false, length = 2000, updatable = false)
    private String reason;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recorded_by", nullable = false, updatable = false)
    private UserProfile recordedBy;

    public static TicketFinding record(
            SupportTicket ticket,
            Booking booking,
            TicketFindingConclusion conclusion,
            Instant affectedAt,
            String reason,
            Instant recordedAt,
            UserProfile recordedBy
    ) {
        if (ticket == null || booking == null || conclusion == null || affectedAt == null
                || recordedAt == null || recordedBy == null) {
            throw new IllegalArgumentException("Complete ticket finding evidence is required");
        }
        if (affectedAt.isAfter(recordedAt)) {
            throw new IllegalArgumentException("Affected time cannot be after the finding record time");
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isEmpty() || normalizedReason.length() > 2000) {
            throw new IllegalArgumentException("Finding reason must be non-blank and fit 2000 characters");
        }

        TicketFinding finding = new TicketFinding();
        finding.ticket = ticket;
        finding.booking = booking;
        finding.conclusion = conclusion;
        finding.affectedAt = affectedAt;
        finding.reason = normalizedReason;
        finding.recordedAt = recordedAt;
        finding.recordedBy = recordedBy;
        return finding;
    }
}
