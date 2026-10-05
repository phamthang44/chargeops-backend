package com.thang.chargeops.support.service;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.dto.request.FindingRequest;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.entity.TicketFinding;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketEscalationRepository;
import com.thang.chargeops.support.repository.TicketFindingRepository;
import com.thang.chargeops.support.service.support.TicketResponseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketFindingService {
    private final CurrentProfileProvider currentProfile;
    private final TicketAccessPolicy access;
    private final SupportTicketRepository tickets;
    private final TicketFindingRepository findings;
    private final TicketEscalationRepository escalations;
    private final TicketResponseService responses;
    private final Clock clock;

    @Transactional
    public TicketResponse record(UUID ticketId, FindingRequest request) {
        var actor = currentProfile.requireProfile();
        var ticket = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        boolean admin = access.hasRole("ADMIN");
        boolean allowed = admin ? access.canRead(ticket, actor)
                : access.isOwner(ticket, actor.getId()) || access.isStaff(ticket, actor.getId());
        if (actor.getStatus() != UserStatus.ACTIVE || !allowed) {
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        }
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new AppException(TicketErrorCode.CLOSED);
        }
        if (ticket.getStation() == null || ticket.getBooking() == null) {
            throw new AppException(TicketErrorCode.FINDING_INVALID);
        }
        if (!request.expectedVersion().equals(ticket.getVersion())) {
            throw new AppException(TicketErrorCode.VERSION_CONFLICT);
        }
        if (admin && (ticket.getStatus() == TicketStatus.RESOLVED
                || !escalations.existsByTicket_IdAndResolvedAtIsNull(ticketId))) {
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        }
        if (!admin) {
            if (ticket.getStatus() == TicketStatus.RESOLVED) {
                throw new AppException(TicketErrorCode.ACCESS_DENIED);
            }
            if (escalations.existsByTicket_IdAndResolvedAtIsNull(ticketId)) {
                throw new AppException(TicketErrorCode.ACCESS_DENIED);
            }
        }
        var latestFinding = findings.findFirstByTicket_IdOrderByRecordedAtDescIdDesc(ticketId);
        var bookingStation = ticket.getBooking().getConnector().getChargePoint().getStation();
        if (!ticket.getStation().getId().equals(bookingStation.getId())) {
            throw new AppException(TicketErrorCode.FINDING_INVALID);
        }
        Instant now = clock.instant();
        if (request.affectedAt().isAfter(now)) {
            throw new AppException(TicketErrorCode.FINDING_INVALID);
        }
        Instant recordedAt = latestFinding.isPresent() && !now.isAfter(latestFinding.get().getRecordedAt())
                ? latestFinding.get().getRecordedAt().plusNanos(1) : now;
        if (ticket.getResolvedAt() != null && !recordedAt.isAfter(ticket.getResolvedAt())) {
            recordedAt = ticket.getResolvedAt().plusNanos(1);
        }
        findings.saveAndFlush(TicketFinding.record(ticket, ticket.getBooking(), request.conclusion(),
                request.affectedAt(), request.reason(), recordedAt, actor));
        // A finding is independent of status, but it still advances the aggregate version.
        // This makes a replay with the old expectedVersion return 409.
        Instant previousUpdate = ticket.getUpdatedAt();
        ticket.setUpdatedAt(previousUpdate != null && !recordedAt.isAfter(previousUpdate)
                ? previousUpdate.plusNanos(1) : recordedAt);
        tickets.saveAndFlush(ticket);
        return responses.toResponse(ticket);
    }
}
