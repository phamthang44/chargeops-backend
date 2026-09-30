package com.thang.chargeops.support.service;


import com.thang.chargeops.support.dto.request.CreateTicketRequest;
import com.thang.chargeops.support.dto.request.MessageRequest;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.model.TicketStatus;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface SupportTicketService {

    TicketResponse create(CreateTicketRequest request);

    Page<TicketResponse> getTickets(TicketStatus status, UUID stationId, int page, int size);

    TicketResponse getTicket(UUID ticketId);

    TicketMessageResponse replyTicket(UUID ticketId, UUID clientMessageId, MessageRequest request);
}

