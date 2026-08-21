package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.LicenseStatusActorType;
import com.thang.chargeops.common.enums.LicenseStatusEventType;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "license_status_events", indexes = {
        @Index(name = "idx_license_status_events_license_time", columnList = "license_id, performed_at"),
        @Index(name = "idx_license_status_events_event_time", columnList = "event_type, performed_at")
})
public class LicenseStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "license_id", nullable = false, updatable = false)
    private License license;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 30)
    private LicenseStatusEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 30)
    private LicenseStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 30)
    private LicenseStatus toStatus;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
    private LicenseStatusActorType actorType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by", updatable = false)
    private UserProfile performedBy;

    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;

    public static LicenseStatusEvent recordedByUser(
            License license,
            LicenseStatusEventType eventType,
            LicenseStatus fromStatus,
            LicenseStatus toStatus,
            UserProfile performedBy,
            Instant performedAt,
            String reason
    ) {
        if (performedBy == null) {
            throw new IllegalArgumentException("License status event performer cannot be null");
        }

        return create(
                license,
                eventType,
                fromStatus,
                toStatus,
                LicenseStatusActorType.USER,
                performedBy,
                performedAt,
                reason
        );
    }

    public static LicenseStatusEvent recordedBySystem(
            License license,
            LicenseStatusEventType eventType,
            LicenseStatus fromStatus,
            LicenseStatus toStatus,
            Instant performedAt,
            String reason
    ) {
        return create(
                license,
                eventType,
                fromStatus,
                toStatus,
                LicenseStatusActorType.SYSTEM,
                null,
                performedAt,
                reason
        );
    }

    private static LicenseStatusEvent create(
            License license,
            LicenseStatusEventType eventType,
            LicenseStatus fromStatus,
            LicenseStatus toStatus,
            LicenseStatusActorType actorType,
            UserProfile performedBy,
            Instant performedAt,
            String reason
    ) {
        if (license == null) {
            throw new IllegalArgumentException("License status event license cannot be null");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("License status event type cannot be null");
        }
        if (!eventType.supports(fromStatus, toStatus)) {
            throw new IllegalArgumentException("Invalid license status event transition");
        }
        if (performedAt == null) {
            throw new IllegalArgumentException("License status event time cannot be null");
        }

        LicenseStatusEvent event = new LicenseStatusEvent();
        event.license = license;
        event.eventType = eventType;
        event.fromStatus = fromStatus;
        event.toStatus = toStatus;
        event.reason = normalizeReason(reason);
        event.actorType = actorType;
        event.performedBy = performedBy;
        event.performedAt = performedAt;
        return event;
    }

    @Override
    public String toString() {
        return "LicenseStatusEvent{" +
                "id=" + id +
                ", licenseId=" + license.getId() +
                ", eventType=" + eventType +
                ", fromStatus=" + fromStatus +
                ", toStatus=" + toStatus +
                ", actorType=" + actorType +
                '}';
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }

        return reason.trim();
    }
}
