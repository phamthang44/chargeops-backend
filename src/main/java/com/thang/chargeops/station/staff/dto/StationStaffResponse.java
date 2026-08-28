package com.thang.chargeops.station.staff.dto;

import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record StationStaffResponse(
        UUID assignmentId,
        UUID stationId,
        String stationName,
        UUID userId,
        String email,
        String displayName,
        String maskedPhone,
        StaffAssignmentStatus status,
        String note,
        UUID assignedBy,
        Instant assignedAt,
        UUID revokedBy,
        Instant revokedAt
) {
}
