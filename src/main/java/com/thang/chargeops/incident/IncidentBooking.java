package com.thang.chargeops.incident;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.common.enums.BookingStatus;
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

@Entity
@Table(name = "incident_bookings", uniqueConstraints = @UniqueConstraint(columnNames = {"incident_id", "booking_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IncidentBooking extends BaseEntity {
    public enum State { AWAITING_SESSION_STOP, NO_ACTIVE_SESSION, SESSION_RESOLVED }
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false, updatable = false)
    private ConnectorIncident incident;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    private Booking booking;
    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_status", nullable = false, updatable = false, length = 30)
    private BookingStatus snapshotStatus;
    @Column(name = "snapshot_version", nullable = false, updatable = false)
    private long snapshotVersion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private State state;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private UserProfile resolvedBy;
    @Column(name = "resolution_reason", length = 2000)
    private String resolutionReason;

    public static IncidentBooking snapshot(ConnectorIncident incident, Booking booking) {
        IncidentBooking affected = new IncidentBooking();
        affected.incident = incident;
        affected.booking = booking;
        affected.snapshotStatus = booking.getStatus();
        affected.snapshotVersion = booking.getVersion();
        affected.state = active(booking.getStatus()) ? State.AWAITING_SESSION_STOP : State.NO_ACTIVE_SESSION;
        return affected;
    }
    public void resolve(UserProfile actor, String reason, Instant now) {
        if (state != State.AWAITING_SESSION_STOP) throw new IllegalStateException("Session already resolved");
        state = State.SESSION_RESOLVED;
        resolvedBy = actor;
        resolutionReason = reason;
        resolvedAt = now;
    }
    public static boolean active(BookingStatus status) {
        return status == BookingStatus.CHECKED_IN || status == BookingStatus.CHARGING;
    }
}
