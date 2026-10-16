package com.thang.chargeops.station.staff.dashboard.dto;

import com.thang.chargeops.common.enums.BookingStatus;

import java.time.Instant;
import java.util.UUID;

public record StaffDashboardBookingItem(
        UUID bookingId,
        String bookingCode,
        BookingStatus status,
        UUID connectorId,
        String connectorCode,
        String driverDisplayName,
        Instant startAt,
        Instant endAt,
        Instant checkedInAt,
        Instant checkInDeadline,
        Instant chargingStartedAt
) {}
