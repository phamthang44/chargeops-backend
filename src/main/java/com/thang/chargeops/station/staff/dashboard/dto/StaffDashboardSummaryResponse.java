package com.thang.chargeops.station.staff.dashboard.dto;

import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StaffDashboardSummaryResponse(
        UUID stationId,
        String stationName,
        String address,
        StationStatus stationStatus,
        StationOperationalStatus operationalStatus,
        Instant generatedAt,
        LocalDate date,
        String timezone,
        long bookingsToday,
        long chargingBookings,
        long availableConnectors,
        long unclaimedTickets
) {}
