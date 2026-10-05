package com.thang.chargeops.support;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketMessage;
import com.thang.chargeops.support.model.TicketActorKind;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.repository.TicketFindingRepository;
import com.thang.chargeops.support.repository.TicketMessageRepository;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketEventRepository;
import com.thang.chargeops.support.entity.TicketEvent;
import com.thang.chargeops.support.service.support.TicketResponseMapper;
import com.thang.chargeops.support.service.support.TicketResponseService;
import com.thang.chargeops.support.service.TicketEscalationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupportTicketResponseAssemblerTest {
    @Test
    void detailUsesPersistedWorkflowEventsAndLatestMessageForFrontendFields() throws Exception {
        UUID ticketId = UUID.randomUUID();
        Instant resolvedAt = Instant.parse("2026-10-01T10:00:00Z");
        Instant closedAt = resolvedAt.plusSeconds(600);
        Instant messageAt = resolvedAt.plusSeconds(300);
        UserProfile reporter = UserProfile.builder().email("driver@example.test").displayName("Driver").build();
        reporter.setId(UUID.randomUUID());
        SupportTicket ticket = SupportTicket.open("TKT-20261001-0001", TicketCategory.PAYMENT,
                TicketPriority.MEDIUM, reporter, null, null,
                new SupportTicket.TicketDetails("Payment issue", "Charged twice"));
        ticket.setId(ticketId);
        ticket.prePersistAudit();
        ticket.setUpdatedAt(resolvedAt);
        ticket.assign(reporter);
        ticket.resolve(resolvedAt);
        ticket.close("AUTO_CLOSED_NO_RESPONSE");

        TicketMessageRepository messages = mock(TicketMessageRepository.class);
        TicketFindingRepository findings = mock(TicketFindingRepository.class);
        RefundRepository refunds = mock(RefundRepository.class);
        SupportTicketRepository tickets = mock(SupportTicketRepository.class);
        TicketEventRepository events = mock(TicketEventRepository.class);
        TicketMessage message = mock(TicketMessage.class);
        when(message.getTicket()).thenReturn(ticket);
        when(message.getAuthor()).thenReturn(reporter);
        when(message.getAuthorKind()).thenReturn(TicketActorKind.REPORTER);
        when(message.getBody()).thenReturn("Need an update");
        when(message.getCreatedAt()).thenReturn(messageAt);
        when(messages.findByTicket_IdInOrderByCreatedAtAscIdAsc(List.of(ticketId))).thenReturn(List.of(message));
        when(findings.findByTicket_IdInOrderByRecordedAtAscIdAsc(List.of(ticketId))).thenReturn(List.of());

        TicketEvent closed = mock(TicketEvent.class);
        when(closed.getTicketId()).thenReturn(ticketId);
        when(closed.getEventType()).thenReturn("AUTO_CLOSED_NO_RESPONSE");
        when(closed.getCreatedAt()).thenReturn(closedAt);
        TicketEvent resolved = mock(TicketEvent.class);
        when(resolved.getTicketId()).thenReturn(ticketId);
        when(resolved.getEventType()).thenReturn("RESOLVED");
        when(resolved.getReason()).thenReturn("Refund reviewed");
        when(events.findWorkflowFacts(eq(List.of(ticketId)), any())).thenReturn(List.of(closed, resolved));

        var response = new TicketResponseService(messages, findings, refunds, tickets, events,
                new TicketResponseMapper(), mock(TicketEscalationService.class)).toResponse(ticket);
        assertThat(response.description()).isEqualTo("Charged twice");
        assertThat(response.reporterName()).isEqualTo("Driver");
        assertThat(response.assignedHandlerName()).isEqualTo("Driver");
        assertThat(response.resolutionReason()).isEqualTo("Refund reviewed");
        assertThat(response.closedAt()).isEqualTo(closedAt);
        assertThat(response.updatedAt()).isEqualTo(closedAt);
        assertThat(response.lastMessagePreview()).isEqualTo("Need an update");
        assertThat(response.messageCount()).isEqualTo(1);
        verify(events).findWorkflowFacts(eq(List.of(ticketId)), any());
    }
}
