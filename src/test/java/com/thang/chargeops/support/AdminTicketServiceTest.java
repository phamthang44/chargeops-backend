package com.thang.chargeops.support;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.support.dto.request.AssignTicketRequest;
import com.thang.chargeops.support.dto.request.MessageRequest;
import com.thang.chargeops.support.dto.request.TicketStatusRequest;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.service.AdminTicketService;
import com.thang.chargeops.support.service.SupportTicketService;
import com.thang.chargeops.support.service.TicketAccessPolicy;
import com.thang.chargeops.support.service.impl.TicketWorkflowService;
import com.thang.chargeops.support.service.support.TicketResponseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminTicketServiceTest {
    @Mock CurrentProfileProvider currentProfile;
    @Mock TicketAccessPolicy access;
    @Mock SupportTicketRepository tickets;
    @Mock TicketResponseService responses;
    @Mock TicketWorkflowService workflow;
    @Mock SupportTicketService ticketService;
    @InjectMocks AdminTicketService service;

    private UserProfile admin;
    private final UUID ticketId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        admin = UserProfile.builder().status(UserStatus.ACTIVE).email("admin@chargeops.test").build();
        admin.setId(UUID.randomUUID());
        lenient().when(currentProfile.requireProfile()).thenReturn(admin);
        lenient().when(access.hasRole("ADMIN")).thenReturn(true);
    }

    @Test
    void nonAdminCannotAccessService() {
        when(access.hasRole("ADMIN")).thenReturn(false);
        assertThatThrownBy(() -> service.get(ticketId))
                .isInstanceOf(AppException.class);
    }

    @Test
    void adminCannotClaimStationTicket() {
        SupportTicket stationTicket = mock(SupportTicket.class);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(stationTicket));
        when(stationTicket.getStation()).thenReturn(mock(Station.class));

        assertThatThrownBy(() -> service.claim(ticketId, 0L))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(workflow);
    }

    @Test
    void adminCannotChangeStatusOfStationTicket() {
        SupportTicket stationTicket = mock(SupportTicket.class);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(stationTicket));
        when(stationTicket.getStation()).thenReturn(mock(Station.class));

        assertThatThrownBy(() -> service.changeStatus(ticketId,
                new TicketStatusRequest(0L, TicketStatus.RESOLVED, "Resolved by admin")))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(workflow);
    }

    @Test
    void adminCanClaimPlatformTicket() {
        SupportTicket platformTicket = mock(SupportTicket.class);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(platformTicket));
        when(platformTicket.getStation()).thenReturn(null);

        service.claim(ticketId, 0L);
        verify(workflow).claim(ticketId, 0L);
    }

    @Test
    void adminCannotRouteStationTicketToStaff() {
        AssignTicketRequest request = new AssignTicketRequest(0L, UUID.randomUUID(), "Routing to staff");
        SupportTicket stationTicket = mock(SupportTicket.class);
        when(stationTicket.getStation()).thenReturn(mock(Station.class));
        when(tickets.findById(ticketId)).thenReturn(Optional.of(stationTicket));
        assertThatThrownBy(() -> service.assign(ticketId, request)).isInstanceOf(AppException.class);
        verifyNoInteractions(workflow);
    }

    @Test
    void adminCanReplyToEscalatedStationTicket() {
        MessageRequest request = new MessageRequest("Admin comment without claiming");
        UUID clientMessageId = UUID.randomUUID();
        SupportTicket stationTicket = mock(SupportTicket.class);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(stationTicket));
        when(access.canRead(stationTicket, admin)).thenReturn(true);
        service.reply(ticketId, clientMessageId, request);
        verify(ticketService).replyAsAdmin(ticketId, clientMessageId, request);
    }

}
