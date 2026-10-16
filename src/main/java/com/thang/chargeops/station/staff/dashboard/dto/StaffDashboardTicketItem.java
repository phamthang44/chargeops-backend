package com.thang.chargeops.station.staff.dashboard.dto;

import com.thang.chargeops.support.model.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record StaffDashboardTicketItem(
        UUID ticketId,
        String ticketCode,
        String subject,
        TicketStatus status,
        Instant createdAt,
        UUID assignedHandlerId,
        String assignedHandlerName
) {}
