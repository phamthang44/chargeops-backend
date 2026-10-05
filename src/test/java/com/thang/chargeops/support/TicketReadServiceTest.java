package com.thang.chargeops.support;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketEvent;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketEventRepository;
import com.thang.chargeops.support.service.TicketAccessPolicy;
import com.thang.chargeops.support.service.TicketEventQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TicketReadServiceTest {
    @Test
    void eventResponseIncludesActorAndHandlerNames() {
        UUID ticketId = UUID.randomUUID();
        UserProfile reporter = UserProfile.builder().email("driver@example.test").build();
        reporter.setId(UUID.randomUUID());
        UserProfile actor = UserProfile.builder().displayName("Station owner").build();
        actor.setId(UUID.randomUUID());
        UserProfile handler = UserProfile.builder().displayName("Staff member").build();
        handler.setId(UUID.randomUUID());
        SupportTicket ticket = SupportTicket.open("TKT-20261001-0001", TicketCategory.PAYMENT,
                TicketPriority.MEDIUM, reporter, null, null,
                new SupportTicket.TicketDetails("Payment issue", "Charged twice"));
        ticket.setId(ticketId);

        TicketEvent event = mock(TicketEvent.class);
        when(event.getId()).thenReturn(UUID.randomUUID());
        when(event.getActorId()).thenReturn(actor.getId());
        when(event.getToHandlerId()).thenReturn(handler.getId());
        when(event.getActorKind()).thenReturn("OWNER");
        when(event.getEventType()).thenReturn("ASSIGNED");

        TicketEventRepository events = mock(TicketEventRepository.class);
        SupportTicketRepository tickets = mock(SupportTicketRepository.class);
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        CurrentProfileProvider currentProfile = mock(CurrentProfileProvider.class);
        TicketAccessPolicy access = mock(TicketAccessPolicy.class);
        when(currentProfile.requireProfile()).thenReturn(reporter);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(access.canRead(ticket, reporter)).thenReturn(true);
        when(events.findByTicketId(eq(ticketId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(profiles.findAllById(any())).thenReturn(List.of(actor, handler));

        var result = new TicketEventQueryService(events, tickets, profiles, currentProfile, access)
                .events(ticketId, 1, 20).getContent().getFirst();
        assertThat(result.actorName()).isEqualTo("Station owner");
        assertThat(result.toHandlerName()).isEqualTo("Staff member");
        assertThat(result.toHandlerId()).isEqualTo(handler.getId());
        assertThat(result.actorId()).isEqualTo(actor.getId());
    }
}
