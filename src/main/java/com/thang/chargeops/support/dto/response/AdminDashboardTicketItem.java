package com.thang.chargeops.support.dto.response;

import com.thang.chargeops.support.model.TicketStatus;

import java.time.Instant;
import java.util.UUID;

/** Minimal operational queue row. Financial and message data never enter this DTO. */
public record AdminDashboardTicketItem(
        UUID ticketId,
        String ticketCode,
        String subject,
        TicketStatus status,
        Instant createdAt,
        String reporterName,
        UUID stationId,
        String stationName,
        String escalationReason
) {}
