package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.enums.StationOperationalStatus;
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
@Table(name = "station_operational_status_events", indexes = {
        @Index(
                name = "idx_station_operational_events_station_time",
                columnList = "station_id, performed_at"
        )
})
public class StationOperationalStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false, updatable = false)
    private Station station;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, updatable = false, length = 30)
    private StationOperationalStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 30)
    private StationOperationalStatus toStatus;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by", nullable = false, updatable = false)
    private UserProfile performedBy;

    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;

    public static StationOperationalStatusEvent transition(
            Station station,
            StationOperationalStatus fromStatus,
            StationOperationalStatus toStatus,
            UserProfile performedBy,
            Instant performedAt,
            String reason
    ) {
        if (station == null || fromStatus == null || toStatus == null) {
            throw new IllegalArgumentException("Station operational transition is incomplete");
        }
        if (fromStatus == toStatus) {
            throw new IllegalArgumentException("Station operational transition must change status");
        }
        if (performedBy == null || performedAt == null) {
            throw new IllegalArgumentException("Station operational transition requires actor and time");
        }

        StationOperationalStatusEvent event = new StationOperationalStatusEvent();
        event.station = station;
        event.fromStatus = fromStatus;
        event.toStatus = toStatus;
        event.reason = normalizeReason(reason);
        event.performedBy = performedBy;
        event.performedAt = performedAt;
        return event;
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }
}
