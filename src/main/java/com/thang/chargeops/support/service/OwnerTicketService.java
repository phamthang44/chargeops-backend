package com.thang.chargeops.support.service;

import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.support.dto.request.AssignTicketRequest;
import com.thang.chargeops.support.dto.request.MessageRequest;
import com.thang.chargeops.support.dto.request.TicketStatusRequest;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.dto.response.TicketDetailResponse;
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

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OwnerTicketService {
    private final CurrentProfileProvider currentProfile;
    private final TicketAccessPolicy access;
    private final StationRepository stations;
    private final SupportTicketRepository tickets;
    private final TicketResponseService responses;
    private final TicketWorkflowService workflow;
    private final SupportTicketService ticketService;

    private UUID ownerId() {
        if (!access.hasRole("OWNER")) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return currentProfile.requireProfile().getId();
    }

    private SupportTicket owned(UUID ticketId) {
        UUID actorId = ownerId();
        SupportTicket ticket = tickets.findById(ticketId).orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (!access.isOwner(ticket, actorId)) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return ticket;
    }

    @Transactional(readOnly = true)
    public Page<TicketResponse> list(UUID stationId, TicketStatus status, int page, int size) {
        UUID actorId = ownerId();
        Set<UUID> stationIds = stations.findAllByOwner_Id(actorId).stream()
                .map(BaseEntity::getId).collect(Collectors.toSet());
        if (stationId != null && !stationIds.contains(stationId)) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        var pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Specification<SupportTicket> spec = (root, query, cb) -> cb.and(
                root.get("station").get("id").in(stationId == null ? stationIds : Set.of(stationId)),
                status == null ? cb.conjunction() : cb.equal(root.get("status"), status));
        Page<SupportTicket> result = tickets.findAll(spec, pageable);
        return new PageImpl<>(responses.toResponses(result.getContent()), pageable, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public TicketResponse get(UUID ticketId) {
        return responses.toResponse(owned(ticketId));
    }

    @Transactional(readOnly = true)
    public TicketDetailResponse getDetail(UUID ticketId) {
        return responses.toDetail(owned(ticketId));
    }

    @Transactional(readOnly = true)
    public void requireOwned(UUID ticketId) {
        owned(ticketId);
    }

    @Transactional
    public TicketResponse claim(UUID ticketId, long expectedVersion) {
        owned(ticketId);
        return workflow.claim(ticketId, expectedVersion);
    }

    @Transactional
    public TicketResponse assign(UUID ticketId, AssignTicketRequest request) {
        owned(ticketId);
        return workflow.assign(ticketId, request);
    }

    @Transactional
    public TicketResponse changeStatus(UUID ticketId, TicketStatusRequest request) {
        owned(ticketId);
        if (request.status() == TicketStatus.CLOSED) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        return workflow.changeStatus(ticketId, request);
    }

    @Transactional
    public TicketMessageResponse reply(UUID ticketId, UUID clientMessageId, MessageRequest request) {
        owned(ticketId);
        return ticketService.replyTicket(ticketId, clientMessageId, request);
    }
}
