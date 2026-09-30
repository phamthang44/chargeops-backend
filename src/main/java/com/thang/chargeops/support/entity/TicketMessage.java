package com.thang.chargeops.support.entity;

import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.support.model.TicketActorKind;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "ticket_messages", indexes = {
        @Index(name = "idx_ticket_messages_ticket_created", columnList = "ticket_id, created_at, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketMessage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private SupportTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private UserProfile author;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_kind", nullable = false, length = 20, updatable = false)
    private TicketActorKind authorKind;

    @Column(nullable = false, length = 2000, updatable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static TicketMessage create(
            SupportTicket ticket,
            UserProfile author,
            TicketActorKind authorKind,
            String body,
            Instant createdAt
    ) {
        if (ticket == null || author == null || authorKind == null || createdAt == null) {
            throw new IllegalArgumentException("Ticket, author, actor kind and creation time are required");
        }
        String normalizedBody = body == null ? "" : body.trim();
        if (normalizedBody.isEmpty() || normalizedBody.length() > 2000) {
            throw new IllegalArgumentException("Message body must be non-blank and fit 2000 characters");
        }

        TicketMessage message = new TicketMessage();
        message.ticket = ticket;
        message.author = author;
        message.authorKind = authorKind;
        message.body = normalizedBody;
        message.createdAt = createdAt;
        return message;
    }
}
