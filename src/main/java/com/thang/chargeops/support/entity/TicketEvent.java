package com.thang.chargeops.support.entity;

import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.support.model.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket_events")
@Getter
@NoArgsConstructor
public class TicketEvent extends BaseEntity {
    public record EventActor(UUID id, String kind) {
    }

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;
    @Column(name = "actor_id")
    private UUID actorId;
    @Column(name = "actor_kind", nullable = false, length = 20, updatable = false)
    private String actorKind;
    @Column(name = "event_type", nullable = false, length = 40, updatable = false)
    private String eventType;
    @Column(name = "from_status", length = 20, updatable = false)
    private String fromStatus;
    @Column(name = "to_status", length = 20, updatable = false)
    private String toStatus;
    @Column(name = "from_handler_id")
    private UUID fromHandlerId;
    @Column(name = "to_handler_id")
    private UUID toHandlerId;
    @Column(name = "to_handler_kind", length = 20, updatable = false)
    private String toHandlerKind;
    @Column(name = "resolution_cycle", nullable = false)
    private int resolutionCycle;
    @Column(name = "reason", length = 2000, updatable = false)
    private String reason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static TicketEvent create(SupportTicket ticket, EventActor actor, String eventType,
                                     TicketStatus before, UUID previousHandlerId, String reason, Instant at) {
        TicketEvent event = new TicketEvent();
        UUID handlerId = ticket.getAssignedHandler() == null ? null : ticket.getAssignedHandler().getId();
        event.ticketId = ticket.getId();
        event.actorId = actor.id();
        event.actorKind = actor.kind();
        event.eventType = eventType;
        event.fromStatus = before.name();
        event.toStatus = ticket.getStatus().name();
        event.fromHandlerId = previousHandlerId;
        event.toHandlerId = handlerId;
        event.toHandlerKind = handlerKind(ticket, handlerId);
        event.resolutionCycle = ticket.getResolutionCycle();
        event.reason = reason;
        event.createdAt = at;
        return event;
    }

    private static String handlerKind(SupportTicket ticket, UUID handlerId) {
        if (handlerId == null) return null;
        if (ticket.getStation() == null) return "ADMIN";
        return handlerId.equals(ticket.getStation().getOwner().getId()) ? "OWNER" : "STAFF";
    }
}
