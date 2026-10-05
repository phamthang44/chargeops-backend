package com.thang.chargeops.station.staff.service;

import com.thang.chargeops.station.staff.dto.StaffInvitationResponse;
import org.springframework.data.domain.Page;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

public interface StaffInvitationService {
    StaffInvitationResponse invite(UUID stationId, String email);
    StaffInvitationResponse resend(UUID stationId, UUID invitationId);
    Page<StaffInvitationResponse> list(UUID stationId, int page, int size);
    StaffInvitationResponse cancel(UUID stationId, UUID invitationId);
    StaffInvitationResponse activate(Jwt jwt);
}
