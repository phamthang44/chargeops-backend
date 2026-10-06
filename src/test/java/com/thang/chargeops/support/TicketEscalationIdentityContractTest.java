package com.thang.chargeops.support;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.dto.response.TicketEscalationLookupResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketEscalation;
import com.thang.chargeops.support.repository.*;
import com.thang.chargeops.support.service.TicketAccessPolicy;
import com.thang.chargeops.support.service.TicketEscalationService;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TicketEscalationIdentityContractTest {
    @Test
    void lookupAndDetailExposeTheRealEscalationIdInsteadOfTheTicketId() {
        UUID ticketId = UUID.randomUUID(), escalationId = UUID.randomUUID();
        var actor = new UserProfile(); actor.setId(UUID.randomUUID());
        var ticket = mock(SupportTicket.class);
        when(ticket.getId()).thenReturn(ticketId);
        when(ticket.getReporter()).thenReturn(actor);
        var escalation = TicketEscalation.request(ticket, actor, Instant.parse("2026-10-06T06:56:01Z"), "Request review");
        escalation.setId(escalationId);
        var profiles = mock(CurrentProfileProvider.class);
        var access = mock(TicketAccessPolicy.class);
        var tickets = mock(SupportTicketRepository.class);
        var escalations = mock(TicketEscalationRepository.class);
        when(profiles.requireProfile()).thenReturn(actor);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(access.canRead(ticket, actor)).thenReturn(true);
        when(escalations.findByTicket_IdAndResolvedAtIsNull(ticketId)).thenReturn(Optional.of(escalation));
        var service = new TicketEscalationService(profiles, access, tickets, escalations,
                mock(TicketFindingRepository.class), mock(TicketEventRepository.class),
                mock(TicketMessageRepository.class), Clock.systemUTC());

        var response = service.get(ticketId);
        assertThat(response.ticketId()).isEqualTo(ticketId);
        assertThat(response.escalationId()).isEqualTo(escalationId).isNotEqualTo(ticketId);
        assertThat(TicketEscalationLookupResponse.of(response).data().escalationId()).isEqualTo(escalationId);
        assertThat(service.getDetail(ticketId).escalationId()).isEqualTo(escalationId);
        assertThat(service.getDetail(ticketId).requestedByRole()).isEqualTo("driver");
    }
}
