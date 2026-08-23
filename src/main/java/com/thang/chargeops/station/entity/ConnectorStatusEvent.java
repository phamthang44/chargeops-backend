package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Append-only audit event for Connector runtime-status transitions. */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "connector_status_events", indexes = {
        @Index(name = "idx_connector_status_events_connector_time", columnList = "connector_id, performed_at")
})
public class ConnectorStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connector_id", nullable = false, updatable = false)
    private Connector connector;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, updatable = false, length = 30)
    private RuntimeStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 30)
    private RuntimeStatus toStatus;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
    private EquipmentStatusActorType actorType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by", updatable = false)
    private UserProfile performedBy;

    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;

    public static ConnectorStatusEvent userTransition(
            Connector connector,
            RuntimeStatus fromStatus,
            RuntimeStatus toStatus,
            EquipmentStatusActorType actorType,
            UserProfile performedBy,
            Instant performedAt,
            String reason
    ) {
        if (connector == null || fromStatus == null || toStatus == null) {
            throw new IllegalArgumentException("Connector status transition is incomplete");
        }
        if (fromStatus == toStatus) {
            throw new IllegalArgumentException("Connector status transition must change status");
        }
        if (actorType == null || actorType == EquipmentStatusActorType.SYSTEM || performedBy == null) {
            throw new IllegalArgumentException("A user-performed Connector event requires ADMIN or OWNER actor data");
        }
        if (performedAt == null) {
            throw new IllegalArgumentException("Connector status event time cannot be null");
        }

        ConnectorStatusEvent event = new ConnectorStatusEvent();
        event.connector = connector;
        event.fromStatus = fromStatus;
        event.toStatus = toStatus;
        event.reason = reason == null || reason.isBlank() ? null : reason.trim();
        event.actorType = actorType;
        event.performedBy = performedBy;
        event.performedAt = performedAt;
        return event;
    }

    /**
     * Template for T19: booking/session code owns AVAILABLE ↔ IN_USE and must
     * record it as a SYSTEM event instead of impersonating an owner/admin.
     */
    public static ConnectorStatusEvent systemTransition(
            Connector connector,
            RuntimeStatus fromStatus,
            RuntimeStatus toStatus,
            Instant performedAt,
            String reason
    ) {
        if (connector == null || fromStatus == null || toStatus == null || performedAt == null) {
            throw new IllegalArgumentException("Connector system status transition is incomplete");
        }
        if (fromStatus == toStatus) {
            throw new IllegalArgumentException("Connector status transition must change status");
        }

        ConnectorStatusEvent event = new ConnectorStatusEvent();
        event.connector = connector;
        event.fromStatus = fromStatus;
        event.toStatus = toStatus;
        event.reason = reason == null || reason.isBlank() ? null : reason.trim();
        event.actorType = EquipmentStatusActorType.SYSTEM;
        event.performedBy = null;
        event.performedAt = performedAt;
        return event;
    }
}
