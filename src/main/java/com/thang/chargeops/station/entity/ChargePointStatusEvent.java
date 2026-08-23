package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.enums.ChargePointStatusDimension;
import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit event for either charge-point status dimension.
 *
 * <p>Reasons belong to transitions, not to the current ChargePoint row. Keeping
 * them here preserves the complete audit trail instead of overwriting the
 * reason each time the status changes.</p>
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "charge_point_status_events", indexes = {
        @Index(name = "idx_cp_status_events_cp_time", columnList = "charge_point_id, performed_at"),
        @Index(name = "idx_cp_status_events_dimension_time", columnList = "status_dimension, performed_at")
})
public class ChargePointStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "charge_point_id", nullable = false, updatable = false)
    private ChargePoint chargePoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_dimension", nullable = false, updatable = false, length = 20)
    private ChargePointStatusDimension statusDimension;

    @Column(name = "from_status", nullable = false, updatable = false, length = 30)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, updatable = false, length = 30)
    private String toStatus;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
    private EquipmentStatusActorType actorType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by", nullable = false, updatable = false)
    private UserProfile performedBy;

    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;

    public static ChargePointStatusEvent provisioningTransition(
            ChargePoint chargePoint,
            ProvisioningStatus fromStatus,
            ProvisioningStatus toStatus,
            EquipmentStatusActorType actorType,
            UserProfile performedBy,
            Instant performedAt,
            String reason
    ) {
        return create(
                chargePoint,
                ChargePointStatusDimension.PROVISIONING,
                fromStatus,
                toStatus,
                actorType,
                performedBy,
                performedAt,
                reason
        );
    }

    public static ChargePointStatusEvent operationalTransition(
            ChargePoint chargePoint,
            OperationalChargePointStatus fromStatus,
            OperationalChargePointStatus toStatus,
            EquipmentStatusActorType actorType,
            UserProfile performedBy,
            Instant performedAt,
            String reason
    ) {
        return create(
                chargePoint,
                ChargePointStatusDimension.OPERATIONAL,
                fromStatus,
                toStatus,
                actorType,
                performedBy,
                performedAt,
                reason
        );
    }

    private static ChargePointStatusEvent create(
            ChargePoint chargePoint,
            ChargePointStatusDimension dimension,
            Enum<?> fromStatus,
            Enum<?> toStatus,
            EquipmentStatusActorType actorType,
            UserProfile performedBy,
            Instant performedAt,
            String reason
    ) {
        if (chargePoint == null || dimension == null || fromStatus == null || toStatus == null) {
            throw new IllegalArgumentException("Charge-point status transition is incomplete");
        }
        if (fromStatus == toStatus) {
            throw new IllegalArgumentException("Charge-point status transition must change status");
        }
        requireUserActor(actorType, performedBy);
        if (performedAt == null) {
            throw new IllegalArgumentException("Charge-point status event time cannot be null");
        }

        ChargePointStatusEvent event = new ChargePointStatusEvent();
        event.chargePoint = chargePoint;
        event.statusDimension = dimension;
        event.fromStatus = fromStatus.name();
        event.toStatus = toStatus.name();
        event.reason = normalizeReason(reason);
        event.actorType = actorType;
        event.performedBy = performedBy;
        event.performedAt = performedAt;
        return event;
    }

    private static void requireUserActor(EquipmentStatusActorType actorType, UserProfile performedBy) {
        if (actorType == null || actorType == EquipmentStatusActorType.SYSTEM || performedBy == null) {
            throw new IllegalArgumentException("A user-performed equipment event requires ADMIN or OWNER actor data");
        }
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }
}
