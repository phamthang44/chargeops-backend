package com.thang.chargeops.support.entity;

import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "ticket_escalations", uniqueConstraints =
        @UniqueConstraint(name = "ux_ticket_escalations_ticket", columnNames = "ticket_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketEscalation extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private SupportTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false, updatable = false)
    private UserProfile requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(nullable = false, length = 2000, updatable = false)
    private String reason;

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
