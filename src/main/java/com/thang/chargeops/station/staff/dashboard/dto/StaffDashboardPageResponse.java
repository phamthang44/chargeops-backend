package com.thang.chargeops.station.staff.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StaffDashboardPageResponse<T>(
        UUID stationId,
        Instant generatedAt,
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
