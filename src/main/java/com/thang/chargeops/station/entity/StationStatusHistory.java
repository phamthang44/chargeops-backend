package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationStatusEventType;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "station_status_history", indexes = {
        @Index(name = "idx_station_status_history_station_time", columnList = "station_id, performed_at"),
        @Index(name = "idx_station_status_history_event_time", columnList = "event_type, performed_at")
})
public class StationStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private StationStatusEventType stationStatusEventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private StationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private StationStatus toStatus;

    @Column(length = 500)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by", nullable = false)
    private UserProfile performedBy;

    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;

}
