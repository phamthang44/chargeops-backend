package com.thang.chargeops.support;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.infra.identity.IdentityRoleService;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.support.dto.request.TicketStatusRequest;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketEventRepository;
import com.thang.chargeops.support.entity.TicketEvent;
import com.thang.chargeops.notification.service.NotificationService;
import com.thang.chargeops.support.service.TicketAccessPolicy;
import com.thang.chargeops.support.service.impl.TicketWorkflowService;
import com.thang.chargeops.support.service.support.TicketResponseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketWorkflowServiceTest {
    @Mock SupportTicketRepository tickets;
    @Mock UserProfileRepository profiles;
    @Mock IdentityRoleService identityRoles;
    @Mock CurrentProfileProvider currentProfile;
    @Mock StationStaffAssignmentRepository staffAssignments;
    @Mock TicketResponseService assembler;
    @Mock Clock clock;
    @Mock TicketEventRepository events;
    @Mock NotificationService notifications;
    @Mock TicketAccessPolicy access;
    @InjectMocks TicketWorkflowService workflow;

    private final UUID ticketId = UUID.randomUUID();
    private UserProfile admin;
    private UserProfile reporter;
    private SupportTicket ticket;
    private final Instant now = Instant.parse("2026-10-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        admin = UserProfile.builder().status(UserStatus.ACTIVE).email("admin@example.test").build();
        admin.setId(UUID.randomUUID());
        reporter = UserProfile.builder().status(UserStatus.ACTIVE).email("driver@example.test").build();
        reporter.setId(UUID.randomUUID());
        ticket = SupportTicket.open("TKT-20261001-0001", TicketCategory.PAYMENT, TicketPriority.MEDIUM,
            reporter, null, null, new SupportTicket.TicketDetails("Payment issue", "Charge disputed"));
        ticket.setId(ticketId);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void claimRequiresFreshVersionAndUnassignedOpenState() {
        when(access.hasRole("ADMIN")).thenReturn(true);
        when(access.canHandle(eq(ticket), eq(admin.getId()))).thenReturn(true);
        when(currentProfile.requireProfile()).thenReturn(admin);
        when(tickets.findScope(ticketId)).thenReturn(Optional.of(mock(SupportTicketRepository.TicketScope.class)));
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(clock.instant()).thenReturn(now);

        workflow.claim(ticketId, 0);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.getAssignedHandler()).isEqualTo(admin);
        verify(events, times(1)).saveAndFlush(any(TicketEvent.class));

        assertThatThrownBy(() -> workflow.claim(ticketId, 0)).isInstanceOf(AppException.class);
        verify(events, times(1)).saveAndFlush(any(TicketEvent.class));
    }

    @Test
    void resolveCreatesNoticeAndExactDeadlineThenReporterCanConfirm() {
        when(access.canHandle(eq(ticket), eq(admin.getId()))).thenReturn(true);
        ticket.assign(admin);
        when(currentProfile.requireProfile()).thenReturn(admin, reporter);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(clock.instant()).thenReturn(now);

        workflow.changeStatus(ticketId, new TicketStatusRequest(0L, TicketStatus.RESOLVED, "Reviewed payment"));
        assertThat(ticket.getAutoCloseAt()).isEqualTo(now.plusSeconds(864000));
        assertThat(ticket.getResolutionCycle()).isEqualTo(1);
        verify(events, times(1)).saveAndFlush(any(TicketEvent.class));

        workflow.changeStatus(ticketId, new TicketStatusRequest(0L, TicketStatus.CLOSED, null));
        assertThat(ticket.getCloseReason()).isEqualTo("REPORTER_CONFIRMED");
        assertThat(ticket.getAutoCloseAt()).isNull();
    }

    @Test
    void autoCloseCannotRunEarlyOrTwice() {
        ticket.assign(admin);
        ticket.resolve(now);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(clock.instant()).thenReturn(now.plusSeconds(864000));

        workflow.autoClose(ticketId, now.plusSeconds(863999));
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        workflow.autoClose(ticketId, now.plusSeconds(864000));
        workflow.autoClose(ticketId, now.plusSeconds(864000));
        assertThat(ticket.getCloseReason()).isEqualTo("AUTO_CLOSED_NO_RESPONSE");
        verify(events, times(1)).saveAndFlush(any(TicketEvent.class));
    }
}
