package com.thang.chargeops.support.service.support;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.support.dto.response.TicketFindingResponse;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketFinding;
import com.thang.chargeops.support.entity.TicketMessage;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.booking.entity.Booking;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class TicketResponseMapper {
    public TicketResponse map(SupportTicket ticket, List<TicketMessageResponse> messages,
                              List<TicketFindingResponse> findings, List<UUID> refundIds,
                              Instant closedAt, String resolutionReason) {
        Booking booking = ticket.getBooking();
        Station station = ticket.getStation();
        UserProfile reporter = ticket.getReporter();
        UserProfile handler = ticket.getAssignedHandler();
        UUID bookingId = booking == null ? null : booking.getId();
        UUID stationId = station == null ? null : station.getId();
        UUID reporterId = ticket.getReporter() == null ? null : ticket.getReporter().getId();
        UUID handlerId = handler == null ? null : handler.getId();
        Instant lastMessageAt = messages.isEmpty() ? null : messages.getLast().createdAt();
        Instant activityAt = latest(ticket.getUpdatedAt(), lastMessageAt);
        return new TicketResponse(
                ticket.getId(), ticket.getTicketCode(), ticket.getCategory(), ticket.getPriority(),
                ticket.getSubject(), ticket.getStatus(), ticket.getVersion(), bookingId, stationId,
                reporterId, handlerId, ticket.getCreatedAt(), messages, findings, refundIds,
                ticket.getResolvedAt(), ticket.getAutoCloseAt(), ticket.getCloseReason(), ticket.getResolutionCycle(),
                ticket.getDescription(),
                stationName(station, booking),
                station == null ? null : station.getStationCode(),
                stationAddress(station, booking),
                station == null || station.getOperationalStatus() == null ? null : station.getOperationalStatus().name(),
                booking == null ? null : booking.getBookingCode(),
                booking == null ? null : booking.getStartAt(),
                booking == null ? null : booking.getEndAt(),
                displayName(reporter),
                reporter == null ? null : reporter.getPhone(),
                displayName(handler),
                handlerKind(handler, station),
                latest(activityAt, closedAt), closedAt, resolutionReason,
                messages.isEmpty() ? null : messages.getLast().body(), messages.size());
    }

    public TicketMessageResponse message(TicketMessage message) {
        if (message == null) return null;
        UserProfile author = message.getAuthor();
        return new TicketMessageResponse(message.getId(), displayName(author), message.getAuthorKind(),
                message.getBody(), message.getCreatedAt(), author == null ? null : author.getId());
    }

    public TicketFindingResponse finding(TicketFinding finding) {
        if (finding == null) return null;
        UUID recordedById = finding.getRecordedBy() == null ? null : finding.getRecordedBy().getId();
        String recordedByRole = null;
        if (finding.getTicket() != null && recordedById != null) {
            Station station = finding.getTicket().getStation();
            if (station != null && station.getOwner() != null && recordedById.equals(station.getOwner().getId())) {
                recordedByRole = "OWNER";
            } else if (finding.getTicket().getReporter() != null && recordedById.equals(finding.getTicket().getReporter().getId())) {
                recordedByRole = "DRIVER";
            } else if (station == null) {
                recordedByRole = "ADMIN";
            } else {
                recordedByRole = "STAFF";
            }
        }
        return new TicketFindingResponse(finding.getId(), finding.getConclusion(), finding.getAffectedAt(),
                finding.getReason(), finding.getRecordedAt(), recordedById, recordedByRole);
    }

    private String stationName(Station station, Booking booking) {
        if (station != null) return station.getName();
        return booking == null ? null : booking.getStationNameSnapshot();
    }

    private String stationAddress(Station station, Booking booking) {
        if (station != null) return station.getAddressLine();
        return booking == null ? null : booking.getStationAddressSnapshot();
    }

    private String handlerKind(UserProfile handler, Station station) {
        if (handler == null) return null;
        if (station == null) return "ADMIN";
        return handler.getId().equals(station.getOwner().getId()) ? "OWNER" : "STAFF";
    }

    private String displayName(UserProfile profile) {
        if (profile == null) return null;
        return profile.getDisplayName() == null || profile.getDisplayName().isBlank()
                ? profile.getEmail() : profile.getDisplayName();
    }

    private Instant latest(Instant left, Instant right) {
        if (left == null) return right;
        return right == null || left.isAfter(right) ? left : right;
    }
}
