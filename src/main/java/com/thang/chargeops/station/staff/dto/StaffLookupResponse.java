package com.thang.chargeops.station.staff.dto;

public record StaffLookupResponse(
        boolean exists,
        String email,
        String displayName,
        String maskedPhone,
        boolean assignable,
        StaffLookupStatus status
) {
}
