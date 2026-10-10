package com.thang.chargeops.profile.dto;

import java.util.UUID;

/** UM-02: response PATCH /admin/users/{id}/status — snapshot sau no-op hoặc transition. */
public record AdminUserStatusResponse(
        UUID profileId,
        String status,
        Long version,
        boolean changed,
        UUID commandId
) {
}
