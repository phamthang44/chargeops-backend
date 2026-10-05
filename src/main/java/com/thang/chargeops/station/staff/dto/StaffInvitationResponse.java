package com.thang.chargeops.station.staff.dto;

import com.thang.chargeops.station.staff.entity.StaffInvitation;
import com.thang.chargeops.station.staff.entity.StaffInvitationStatus;

import java.time.Instant;
import java.util.UUID;

public record StaffInvitationResponse(UUID invitationId, UUID stationId, String email,
                                      StaffInvitationStatus status, Instant expiresAt,
                                      Instant sentAt, Instant acceptedAt) {
    public static StaffInvitationResponse from(StaffInvitation invitation) {
        return new StaffInvitationResponse(invitation.getId(), invitation.getStation().getId(),
                invitation.getEmail(), invitation.getStatus(), invitation.getExpiresAt(),
                invitation.getSentAt(), invitation.getAcceptedAt());
    }
}
