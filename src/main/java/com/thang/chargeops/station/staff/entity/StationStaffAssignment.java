package com.thang.chargeops.station.staff.entity;

import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Station;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Historical station-to-staff relationship. Revocation changes the lifecycle fields instead of
 * deleting the row. Migration V19 enforces at most one ACTIVE assignment per user globally.
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "station_staff_assignments", indexes = {
        @Index(name = "idx_station_staff_assignments_station_status", columnList = "station_id,status"),
        @Index(name = "idx_station_staff_assignments_user", columnList = "user_id")
})
public class StationStaffAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserProfile staff;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StaffAssignmentStatus status = StaffAssignmentStatus.ACTIVE;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "assigned_by", nullable = false, updatable = false)
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void initializeAssignment() {
        if (status == null) {
            status = StaffAssignmentStatus.ACTIVE;
        }
        if (assignedAt == null) {
            assignedAt = Instant.now();
        }
    }

}
