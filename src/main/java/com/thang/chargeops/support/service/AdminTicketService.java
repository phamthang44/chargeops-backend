package com.thang.chargeops.support.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.dto.request.AssignTicketRequest;
import com.thang.chargeops.support.dto.request.MessageRequest;
import com.thang.chargeops.support.dto.request.TicketStatusRequest;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.service.impl.TicketWorkflowService;
import com.thang.chargeops.support.service.support.TicketResponseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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
        Specification<SupportTicket> spec = (root, query, cb) -> cb.and(
                stationAudit ? cb.isNotNull(root.get(STATION_FIELD)) : cb.isNull(root.get(STATION_FIELD)),
                stationId == null ? cb.conjunction() : cb.equal(root.get(STATION_FIELD).get("id"), stationId),
                status == null ? cb.conjunction() : cb.equal(root.get("status"), status));
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
        return responses.toResponse(tickets.findById(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND)));
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
        platform(ticketId);
        return ticketService.replyTicket(ticketId, clientMessageId, request);
    }
}
