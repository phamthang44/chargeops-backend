package com.thang.chargeops.incident;

import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Connector;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "connector_incidents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorIncident extends BaseEntity {
    public enum Status { OPEN, RECOVERED }
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connector_id", nullable = false, updatable = false)
    private Connector connector;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(nullable = false, updatable = false, length = 2000)
    private String reason;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
    @Column(name = "reported_at", nullable = false, updatable = false)
    private Instant reportedAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reported_by", nullable = false, updatable = false)
    private UserProfile reportedBy;
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
    private EquipmentStatusActorType actorType;
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_runtime_status", nullable = false, updatable = false, length = 30)
    private RuntimeStatus previousRuntimeStatus;
    @Column(name = "recovered_at")
    private Instant recoveredAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recovered_by")
    private UserProfile recoveredBy;
    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_actor_type", length = 20)
    private EquipmentStatusActorType recoveryActorType;
    @Column(name = "recovery_reason", length = 2000)
    private String recoveryReason;
    @Version
    @Column(nullable = false)
    private long version;

    public static ConnectorIncident report(Connector connector, UserProfile actor,
            EquipmentStatusActorType actorType, String reason, Instant occurredAt, Instant now) {
        ConnectorIncident incident = new ConnectorIncident();
        incident.connector = connector;
        incident.status = Status.OPEN;
        incident.reason = reason;
        incident.occurredAt = occurredAt;
        incident.reportedAt = now;
        incident.reportedBy = actor;
        incident.actorType = actorType;
        incident.previousRuntimeStatus = connector.getRuntimeStatus();
        return incident;
    }

    public void recover(UserProfile actor, EquipmentStatusActorType actorType, String reason, Instant now) {
        if (status != Status.OPEN) throw new IllegalStateException("Incident is already recovered");
        status = Status.RECOVERED;
        recoveredBy = actor;
        recoveryActorType = actorType;
        recoveryReason = reason;
        recoveredAt = now;
    }
}
