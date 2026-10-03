package com.thang.chargeops.support.service;


import com.thang.chargeops.support.dto.request.CreateTicketRequest;
import com.thang.chargeops.support.dto.request.MessageRequest;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.dto.response.TicketDetailResponse;
import com.thang.chargeops.support.model.TicketListScope;
import com.thang.chargeops.support.model.TicketStatus;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface SupportTicketService {

    TicketResponse create(CreateTicketRequest request);

    Page<TicketResponse> getTickets(TicketStatus status, UUID stationId, int page, int size, TicketListScope scope);

    TicketResponse getTicket(UUID ticketId);

    /**
     * @param scope {@code null}/{@code ACTOR} = quyền đọc mặc định (reporter | owner | staff | admin);
     *              {@code REPORTER} = chỉ người báo cáo được đọc (không gian Driver).
     */
    TicketDetailResponse getTicketDetail(UUID ticketId, TicketListScope scope);

    TicketMessageResponse replyTicket(UUID ticketId, UUID clientMessageId, MessageRequest request);

    TicketMessageResponse replyAsAdmin(UUID ticketId, UUID clientMessageId, MessageRequest request);
}

