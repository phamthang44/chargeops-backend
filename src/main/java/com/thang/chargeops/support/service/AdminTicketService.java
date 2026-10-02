package com.thang.chargeops.support.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.dto.request.AssignTicketRequest;
import com.thang.chargeops.support.dto.request.MessageRequest;
import com.thang.chargeops.support.dto.request.TicketStatusRequest;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketHandlerCandidateResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.dto.response.TicketDetailResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketEscalation;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.service.impl.TicketWorkflowService;
import com.thang.chargeops.support.service.support.TicketResponseService;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.common.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class AdminTicketService {
    private static final String STATION_FIELD = "station";

    private final CurrentProfileProvider currentProfile;
    private final TicketAccessPolicy access;
    private final SupportTicketRepository tickets;
    private final TicketResponseService responses;
    private final TicketWorkflowService workflow;
    private final SupportTicketService ticketService;
    private final StationStaffAssignmentRepository staffAssignments;

    private void requireAdmin() {
        currentProfile.requireProfile();
        if (!access.hasRole("ADMIN")) throw new AppException(TicketErrorCode.ACCESS_DENIED);
    }

    private SupportTicket platform(UUID ticketId) {
        requireAdmin();
        SupportTicket ticket = tickets.findById(ticketId).orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (ticket.getStation() != null) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return ticket;
    }

    private Page<TicketResponse> list(boolean stationAudit, UUID stationId, TicketStatus status, int page, int size) {
        requireAdmin();
        var pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Specification<SupportTicket> spec = (root, query, cb) -> {
            var scope = stationAudit ? cb.isNotNull(root.get(STATION_FIELD)) : cb.isNull(root.get(STATION_FIELD));
            if (stationAudit) {
                var escalated = query.subquery(UUID.class);
                var escalation = escalated.from(TicketEscalation.class);
                escalated.select(escalation.get("ticket").get("id"));
                scope = cb.and(scope, root.get("id").in(escalated));
            }
            return cb.and(scope,
                    stationId == null ? cb.conjunction() : cb.equal(root.get(STATION_FIELD).get("id"), stationId),
                    status == null ? cb.conjunction() : cb.equal(root.get("status"), status));
        };
        Page<SupportTicket> result = tickets.findAll(spec, pageable);
        return new PageImpl<>(responses.toResponses(result.getContent()), pageable, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Page<TicketResponse> platformQueue(TicketStatus status, int page, int size) {
        return list(false, null, status, page, size);
    }

    @Transactional(readOnly = true)
    public Page<TicketResponse> stationAudit(UUID stationId, TicketStatus status, int page, int size) {
        return list(true, stationId, status, page, size);
    }

    @Transactional(readOnly = true)
    public TicketResponse get(UUID ticketId) {
        requireAdmin();
        var ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (!access.canRead(ticket, currentProfile.requireProfile()))
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return responses.toResponse(ticket);
    }

    @Transactional(readOnly = true)
    public TicketDetailResponse getDetail(UUID ticketId) {
        requireAdmin();
        var ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (!access.canRead(ticket, currentProfile.requireProfile()))
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return responses.toDetail(ticket);
    }

    @Transactional(readOnly = true)
    public List<TicketHandlerCandidateResponse> stationHandlers(UUID ticketId) {
        requireAdmin();
        SupportTicket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (!access.canRead(ticket, currentProfile.requireProfile()))
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        if (ticket.getStation() == null) throw new AppException(TicketErrorCode.INVALID_SCOPE);
        var station = ticket.getStation();
        var result = new ArrayList<TicketHandlerCandidateResponse>();
        var owner = station.getOwner();
        if (owner.getStatus() == UserStatus.ACTIVE) {
            result.add(new TicketHandlerCandidateResponse(owner.getId(),
                    owner.getDisplayName() == null || owner.getDisplayName().isBlank()
                            ? owner.getEmail() : owner.getDisplayName(), "OWNER"));
        }
        staffAssignments.findAllByStation_IdAndStatus(station.getId(), StaffAssignmentStatus.ACTIVE,
                PageRequest.of(0, 100)).forEach(assignment -> {
            var staff = assignment.getStaff();
            if (staff.getStatus() == UserStatus.ACTIVE) {
                result.add(new TicketHandlerCandidateResponse(staff.getId(),
                        staff.getDisplayName() == null || staff.getDisplayName().isBlank()
                                ? staff.getEmail() : staff.getDisplayName(), "STAFF"));
            }
        });
        return result;
    }

    @Transactional
    public TicketResponse claim(UUID ticketId, long expectedVersion) {
        platform(ticketId);
        return workflow.claim(ticketId, expectedVersion);
    }

    @Transactional
    public TicketResponse assign(UUID ticketId, AssignTicketRequest request) {
        platform(ticketId);
        return workflow.assign(ticketId, request);
    }

    @Transactional
    public TicketResponse changeStatus(UUID ticketId, TicketStatusRequest request) {
        platform(ticketId);
        if (request.status() == TicketStatus.CLOSED) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return workflow.changeStatus(ticketId, request);
    }

    @Transactional
    public TicketMessageResponse reply(UUID ticketId, UUID clientMessageId, MessageRequest request) {
        requireAdmin();
        var ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (!access.canRead(ticket, currentProfile.requireProfile()))
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return ticketService.replyAsAdmin(ticketId, clientMessageId, request);
    }
}
