package com.thang.chargeops.station.staff.dto;

import java.util.UUID;

public record StaffLookupResponse(
        boolean exists,
        UUID userId,
        String email,
        String displayName,
        String maskedPhone,
        boolean assignable,
        StaffLookupStatus status
) {
}
