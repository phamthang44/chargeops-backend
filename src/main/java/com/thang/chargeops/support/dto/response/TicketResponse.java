package com.thang.chargeops.support.dto.response;

import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketResponse(
        UUID ticketId,
        String ticketCode,
        TicketCategory category,
        TicketPriority priority,
        String subject,
        TicketStatus status,
        Long version,
        UUID bookingId,
        UUID stationId,
        UUID reporterId,
        UUID assignedHandlerId,
        Instant createdAt,
        List<TicketMessageResponse> messages,
        List<TicketFindingResponse> findings,
        List<UUID> refundIds,
        Instant resolvedAt,
        Instant autoCloseAt,
        String closeReason,
        int resolutionCycle,
        String description,
        String stationName,
        String stationCode,
        String stationAddress,
        String stationOperationalStatus,
        String bookingCode,
        Instant bookingStartAt,
        Instant bookingEndAt,
        String reporterName,
        String reporterPhone,
        String assignedHandlerName,
        String assignedHandlerKind,
        Instant updatedAt,
        Instant closedAt,
        String resolutionReason,
        String lastMessagePreview,
        int messageCount
) {
    public TicketResponse(UUID ticketId, String ticketCode, TicketCategory category, TicketPriority priority,
                          String subject, TicketStatus status, Long version, UUID bookingId, UUID stationId,
                          UUID reporterId, UUID assignedHandlerId, Instant createdAt,
                          List<TicketMessageResponse> messages, List<TicketFindingResponse> findings, List<UUID> refundIds,
                          Instant resolvedAt, Instant autoCloseAt, String closeReason, int resolutionCycle) {
        this(ticketId, ticketCode, category, priority, subject, status, version, bookingId, stationId,
                reporterId, assignedHandlerId, createdAt, messages, findings, refundIds,
                resolvedAt, autoCloseAt, closeReason, resolutionCycle,
                null, null, null, null, null, null, null, null, null, null, null, null,
                createdAt, null, null, null, messages == null ? 0 : messages.size());
    }

    public TicketResponse(UUID ticketId, String ticketCode, TicketCategory category, TicketPriority priority,
                          String subject, TicketStatus status, Long version, UUID bookingId, UUID stationId,
                          UUID reporterId, UUID assignedHandlerId, Instant createdAt,
                          List<TicketMessageResponse> messages, List<TicketFindingResponse> findings, List<UUID> refundIds) {
        this(ticketId, ticketCode, category, priority, subject, status, version, bookingId, stationId,
                reporterId, assignedHandlerId, createdAt, messages, findings, refundIds, null, null, null, 0);
    }
}
