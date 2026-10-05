package com.thang.chargeops.incident;

import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incident_commands", uniqueConstraints = @UniqueConstraint(columnNames = {"actor_id", "operation", "request_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IncidentCommand extends BaseEntity {
    public enum Operation { REPORT, RECOVER, RESOLVE_SESSION }
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false, updatable = false)
    private UserProfile actor;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false, updatable = false)
    private ConnectorIncident incident;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private Operation operation;
    @Column(name = "request_key", nullable = false, updatable = false)
    private UUID requestKey;
    @Column(name = "payload_hash", nullable = false, updatable = false, length = 64)
    private String payloadHash;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    public static IncidentCommand success(UserProfile actor, ConnectorIncident incident, Operation operation,
            UUID key, String hash, Instant now) {
        IncidentCommand command = new IncidentCommand();
        command.actor = actor;
        command.incident = incident;
        command.operation = operation;
        command.requestKey = key;
        command.payloadHash = hash;
        command.createdAt = now;
        return command;
    }
}
