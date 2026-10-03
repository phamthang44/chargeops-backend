package com.thang.chargeops.support.entity;

import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.support.model.TicketEscalationClosureReason;
import com.thang.chargeops.support.model.TicketEscalationResolutionType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "ticket_escalations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketEscalation extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private SupportTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false, updatable = false)
    private UserProfile requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(nullable = false, length = 2000, updatable = false)
    private String reason;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private UserProfile resolvedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", length = 40)
    private TicketEscalationResolutionType resolutionType;

    @Column(name = "resolution_note", length = 2000)
    private String resolutionNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "closure_reason", length = 50)
    private TicketEscalationClosureReason closureReason;

    public void review(UserProfile admin, Instant at, TicketEscalationResolutionType action,
                       TicketEscalationClosureReason closureReason, String note) {
        if (resolvedAt != null || admin == null || at == null || action == null || note == null || note.isBlank()) {
            throw new IllegalStateException("Active escalation and review note are required");
        }
        resolvedAt = at;
        resolvedBy = admin;
        resolutionType = action;
        this.closureReason = closureReason;
        resolutionNote = note.strip();
    }

    public static TicketEscalation request(SupportTicket ticket, UserProfile requester, Instant at, String reason) {
        if (ticket == null || requester == null || at == null || reason == null
                || reason.isBlank() || reason.strip().length() > 2000) {
            throw new IllegalArgumentException("Escalation requires ticket, requester, time and reason");
        }
        TicketEscalation escalation = new TicketEscalation();
        escalation.ticket = ticket;
        escalation.requestedBy = requester;
        escalation.requestedAt = at;
        escalation.reason = reason.strip();
        return escalation;
    }
}
