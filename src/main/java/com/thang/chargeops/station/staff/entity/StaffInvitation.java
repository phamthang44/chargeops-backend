package com.thang.chargeops.station.staff.entity;

import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.station.entity.Station;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "station_staff_invitations")
public class StaffInvitation extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false, updatable = false)
    private Station station;

    @Column(nullable = false, updatable = false, length = 255)
    private String email;

    @Column(name = "identity_user_id", unique = true, length = 255)
    private String identityUserId;

    @Column(name = "invited_by", nullable = false, updatable = false)
    private UUID invitedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StaffInvitationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(nullable = false)
    private long version;

    public static StaffInvitation pending(Station station, String email, UUID invitedBy,
                                           Instant now, Instant expiresAt) {
        StaffInvitation invitation = new StaffInvitation();
        invitation.station = station;
        invitation.email = email;
        invitation.invitedBy = invitedBy;
        invitation.status = StaffInvitationStatus.PENDING;
        invitation.createdAt = now;
        invitation.updatedAt = now;
        invitation.expiresAt = expiresAt;
        return invitation;
    }

    public void sent(String identityUserId, Instant now, Instant expiresAt) {
        if (status != StaffInvitationStatus.PENDING && status != StaffInvitationStatus.SENT) {
            throw new IllegalStateException("Invitation cannot be sent");
        }
        if (this.identityUserId != null && !this.identityUserId.equals(identityUserId)) {
            throw new IllegalStateException("Invitation identity cannot change");
        }
        this.identityUserId = identityUserId;
        status = StaffInvitationStatus.SENT;
        sentAt = now;
        updatedAt = now;
        this.expiresAt = expiresAt;
    }

    public void accept(Instant now) {
        if (status != StaffInvitationStatus.SENT || !now.isBefore(expiresAt)) {
            throw new IllegalStateException("Invitation cannot be accepted");
        }
        status = StaffInvitationStatus.ACCEPTED;
        acceptedAt = now;
        updatedAt = now;
    }

    public void cancel(Instant now) {
        if (status != StaffInvitationStatus.PENDING && status != StaffInvitationStatus.SENT) {
            throw new IllegalStateException("Invitation cannot be cancelled");
        }
        status = StaffInvitationStatus.CANCELLED;
        cancelledAt = now;
        updatedAt = now;
    }
}
