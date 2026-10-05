package com.thang.chargeops.station.staff.service;

import java.util.UUID;

/** Identity operations are idempotent for one invitation ID. */
public interface StaffIdentityProvisioner {
    String ensureStaffIdentity(UUID invitationId, String email);
    void sendActivationEmail(String identityUserId);
}
