package com.thang.chargeops.support;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.support.dto.request.TicketStatusRequest;
import com.thang.chargeops.support.dto.response.TicketDetailResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.service.OwnerTicketService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OwnerTicketServiceTest {
    @Mock CurrentProfileProvider currentProfile;
    @Mock TicketAccessPolicy access;
    @Mock StationRepository stations;
    @Mock SupportTicketRepository tickets;
    @Mock TicketResponseService responses;
    @Mock TicketWorkflowService workflow;
    @Mock SupportTicketService ticketService;
    @InjectMocks OwnerTicketService service;

    private UserProfile owner;
    private final UUID ticketId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        owner = UserProfile.builder().status(UserStatus.ACTIVE).email("owner@chargeops.test").build();
        owner.setId(UUID.randomUUID());
        lenient().when(currentProfile.requireProfile()).thenReturn(owner);
        lenient().when(access.hasRole("OWNER")).thenReturn(true);
    }

    @Test
    void nonOwnerCannotAccess() {
        when(access.hasRole("OWNER")).thenReturn(false);
        assertThatThrownBy(() -> service.get(ticketId))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(TicketErrorCode.ACCESS_DENIED));
    }

    @Test
    void ownerCanReadTicketIfOwnerIsReporterEvenWithoutOwnedStation() {
        SupportTicket ticket = SupportTicket.open("TKT-20261007-0001", TicketCategory.ACCOUNT, TicketPriority.MEDIUM,
                owner, null, null, new SupportTicket.TicketDetails("Subject", "Desc"));
        ticket.setId(ticketId);

        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(access.isOwner(ticket, owner.getId())).thenReturn(false);

        TicketDetailResponse detailResponse = mock(TicketDetailResponse.class);
        when(responses.toDetail(ticket)).thenReturn(detailResponse);

        TicketDetailResponse result = service.getDetail(ticketId);
        assertThat(result).isSameAs(detailResponse);
    }

    @Test
    void ownerCannotReadTicketIfNotOwnerAndNotReporter() {
        UserProfile stranger = UserProfile.builder().status(UserStatus.ACTIVE).email("stranger@test.com").build();
        stranger.setId(UUID.randomUUID());

        SupportTicket ticket = SupportTicket.open("TKT-20261007-0002", TicketCategory.ACCOUNT, TicketPriority.MEDIUM,
                stranger, null, null, new SupportTicket.TicketDetails("Subject", "Desc"));
        ticket.setId(ticketId);

        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(access.isOwner(ticket, owner.getId())).thenReturn(false);

        assertThatThrownBy(() -> service.getDetail(ticketId))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(TicketErrorCode.ACCESS_DENIED));
    }

    @Test
    void ownerReporterCanConfirmClosure() {
        SupportTicket ticket = SupportTicket.open("TKT-20261007-0003", TicketCategory.ACCOUNT, TicketPriority.MEDIUM,
                owner, null, null, new SupportTicket.TicketDetails("Subject", "Desc"));
        ticket.setId(ticketId);

        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(access.isOwner(ticket, owner.getId())).thenReturn(false);

        TicketStatusRequest request = new TicketStatusRequest(0L, TicketStatus.CLOSED, "Reporter confirmed");
        TicketResponse ticketResponse = mock(TicketResponse.class);
        when(workflow.changeStatus(ticketId, request)).thenReturn(ticketResponse);

        TicketResponse result = service.changeStatus(ticketId, request);
        assertThat(result).isSameAs(ticketResponse);
    }

    @Test
    void nonReporterOwnerCannotDirectlyCloseTicket() {
        UserProfile driver = UserProfile.builder().status(UserStatus.ACTIVE).email("driver@test.com").build();
        driver.setId(UUID.randomUUID());

        SupportTicket ticket = SupportTicket.open("TKT-20261007-0004", TicketCategory.CHARGING_ISSUE, TicketPriority.MEDIUM,
                driver, null, null, new SupportTicket.TicketDetails("Subject", "Desc"));
        ticket.setId(ticketId);

        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(access.isOwner(ticket, owner.getId())).thenReturn(true);

        TicketStatusRequest request = new TicketStatusRequest(0L, TicketStatus.CLOSED, "Station owner closing");

        assertThatThrownBy(() -> service.changeStatus(ticketId, request))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(TicketErrorCode.ACCESS_DENIED));
    }
}
