package com.thang.chargeops.support.dto.response;

import com.thang.chargeops.common.enums.StationStatus;

import java.time.Instant;
import java.util.UUID;

/** Submission time follows the canonical approval projection: Station.createdAt. */
public record AdminDashboardApprovalItem(
        UUID stationId,
        String stationCode,
        String stationName,
        String ownerDisplayName,
        String provinceName,
        StationStatus status,
        Instant submittedAt
) {}
