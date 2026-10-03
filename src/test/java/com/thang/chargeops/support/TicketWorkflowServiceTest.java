package com.thang.chargeops.support;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.infra.identity.IdentityRoleService;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.support.dto.request.TicketStatusRequest;
import com.thang.chargeops.support.dto.request.AssignTicketRequest;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.staff.entity.StationStaffAssignment;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketEventRepository;
import com.thang.chargeops.support.repository.TicketEscalationRepository;
import com.thang.chargeops.support.repository.TicketFindingRepository;
import com.thang.chargeops.support.entity.TicketEvent;
import com.thang.chargeops.support.entity.TicketFinding;
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
import java.util.Set;
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
    @Mock TicketEscalationRepository escalations;
    @Mock TicketFindingRepository findings;
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
        when(access.canHandle(eq(ticket), eq(admin.getId()))).thenReturn(true);
        when(currentProfile.requireProfile()).thenReturn(admin);
        when(tickets.findScope(ticketId)).thenReturn(Optional.of(mock(SupportTicketRepository.TicketScope.class)));
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
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
    void reporterExplicitlyContinuesResolvedTicket() {
        ticket.assign(admin);
        ticket.resolve(now);
        when(currentProfile.requireProfile()).thenReturn(reporter);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(identityRoles.getRoles(admin.getKeycloakId())).thenReturn(Set.of(Role.ADMIN));

        workflow.changeStatus(ticketId,
            new TicketStatusRequest(0L, TicketStatus.IN_PROGRESS, "Charger still disconnects"));

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.getAutoCloseAt()).isNull();
        verify(events).saveAndFlush(any(TicketEvent.class));
    }

    @Test
    void stationBookingRequiresFindingBeforeResolution() {
        UserProfile owner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        owner.setId(UUID.randomUUID());
        SupportTicket stationTicket = SupportTicket.open("TKT-20261001-0010", TicketCategory.CHARGING_ISSUE,
            TicketPriority.HIGH, reporter, mock(Station.class), mock(Booking.class),
            new SupportTicket.TicketDetails("Station issue", "Charger failed"));
        stationTicket.setId(ticketId);
        stationTicket.assign(owner);
        when(currentProfile.requireProfile()).thenReturn(owner);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(stationTicket));
        when(access.canHandle(stationTicket, owner.getId())).thenReturn(true);

        assertThatThrownBy(() -> workflow.changeStatus(ticketId,
            new TicketStatusRequest(0L, TicketStatus.RESOLVED, "Repaired charger")))
            .isInstanceOf(AppException.class);
        assertThat(stationTicket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        verify(events, never()).saveAndFlush(any(TicketEvent.class));
        verifyNoInteractions(notifications);
    }

    @Test
    void escalatedStationTicketCannotBeResolvedByHandler() {
        UserProfile owner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        owner.setId(UUID.randomUUID());
        SupportTicket stationTicket = SupportTicket.open("TKT-20261001-0011", TicketCategory.CHARGING_ISSUE,
            TicketPriority.HIGH, reporter, mock(Station.class), mock(Booking.class),
            new SupportTicket.TicketDetails("Station issue", "Disputed charger failure"));
        stationTicket.setId(ticketId);
        stationTicket.assign(owner);
        when(currentProfile.requireProfile()).thenReturn(owner);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(stationTicket));
        when(access.canHandle(stationTicket, owner.getId())).thenReturn(true);
        when(escalations.existsByTicket_IdAndResolvedAtIsNull(ticketId)).thenReturn(true);

        assertThatThrownBy(() -> workflow.changeStatus(ticketId,
            new TicketStatusRequest(0L, TicketStatus.RESOLVED, "Repaired charger")))
            .isInstanceOf(AppException.class);
        assertThat(stationTicket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        verifyNoInteractions(findings, notifications);
    }

    @Test
    void stationBookingWithFindingCanBeResolved() {
        UserProfile owner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        owner.setId(UUID.randomUUID());
        Station station = mock(Station.class);
        when(station.getOwner()).thenReturn(owner);
        SupportTicket stationTicket = SupportTicket.open("TKT-20261001-0012", TicketCategory.CHARGING_ISSUE,
            TicketPriority.HIGH, reporter, station, mock(Booking.class),
            new SupportTicket.TicketDetails("Station issue", "Charger failed"));
        stationTicket.setId(ticketId);
        stationTicket.assign(owner);
        when(currentProfile.requireProfile()).thenReturn(owner);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(stationTicket));
        when(access.canHandle(stationTicket, owner.getId())).thenReturn(true);
        when(findings.findFirstByTicket_IdOrderByRecordedAtDescIdDesc(ticketId))
            .thenReturn(Optional.of(mock(TicketFinding.class)));
        when(clock.instant()).thenReturn(now);

        workflow.changeStatus(ticketId, new TicketStatusRequest(0L, TicketStatus.RESOLVED, "Repaired charger"));

        assertThat(stationTicket.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        verify(notifications).createTicketNotice(eq(reporter.getId()), anyString(), anyString(),
            anyString(), anyString(), eq(now));
    }

    @Test
    void reopenedStationBookingNeedsFindingFromCurrentCycle() {
        UserProfile owner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        owner.setId(UUID.randomUUID());
        Station station = mock(Station.class);
        SupportTicket stationTicket = SupportTicket.open("TKT-20261001-0013", TicketCategory.CHARGING_ISSUE,
            TicketPriority.HIGH, reporter, station, mock(Booking.class),
            new SupportTicket.TicketDetails("Station issue", "Charger failed again"));
        stationTicket.setId(ticketId);
        stationTicket.assign(owner);
        stationTicket.resolve(now.minusSeconds(60));
        stationTicket.continueWork(owner);
        TicketFinding oldFinding = mock(TicketFinding.class);
        when(oldFinding.getRecordedAt()).thenReturn(now.minusSeconds(120));
        when(currentProfile.requireProfile()).thenReturn(owner);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(stationTicket));
        when(access.canHandle(stationTicket, owner.getId())).thenReturn(true);
        when(findings.findFirstByTicket_IdOrderByRecordedAtDescIdDesc(ticketId))
            .thenReturn(Optional.of(oldFinding));

        assertThatThrownBy(() -> workflow.changeStatus(ticketId,
            new TicketStatusRequest(0L, TicketStatus.RESOLVED, "Checked again")))
            .isInstanceOf(AppException.class);
        assertThat(stationTicket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        verifyNoInteractions(notifications);
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

    @Test
    void ownerRoutesOpenStationTicketToActiveStationStaffWithoutClaiming() {
        UUID stationId = UUID.randomUUID();
        UserProfile owner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        owner.setId(UUID.randomUUID());
        UserProfile staff = UserProfile.builder().status(UserStatus.ACTIVE).build();
        staff.setId(UUID.randomUUID());
        Station station = mock(Station.class);
        when(station.getId()).thenReturn(stationId);
        when(station.getOwner()).thenReturn(owner);
        SupportTicket stationTicket = SupportTicket.open("TKT-20261001-0002", TicketCategory.CHARGING_ISSUE,
                TicketPriority.HIGH, reporter, station, null,
                new SupportTicket.TicketDetails("Station issue", "Charger failed"));
        stationTicket.setId(ticketId);
        var scope = mock(SupportTicketRepository.TicketScope.class);
        when(scope.getStationId()).thenReturn(stationId);
        when(scope.getOwnerId()).thenReturn(owner.getId());
        when(currentProfile.requireProfile()).thenReturn(owner);
        when(access.hasRole("OWNER")).thenReturn(true);
        when(access.isOwner(stationTicket, owner.getId())).thenReturn(true);
        when(tickets.findScope(ticketId)).thenReturn(Optional.of(scope));
        when(staffAssignments.findActiveForUpdate(stationId, staff.getId(), StaffAssignmentStatus.ACTIVE))
                .thenReturn(Optional.of(mock(StationStaffAssignment.class)));
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(stationTicket));
        when(profiles.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(staffAssignments.existsByStation_IdAndStaff_IdAndStatus(stationId, staff.getId(), StaffAssignmentStatus.ACTIVE))
                .thenReturn(true);
        when(clock.instant()).thenReturn(now);

        workflow.assign(ticketId, new AssignTicketRequest(0L, staff.getId(), "Route to station technician"));

        assertThat(stationTicket.getAssignedHandler()).isSameAs(staff);
        assertThat(stationTicket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        verify(events).saveAndFlush(any(TicketEvent.class));
    }

    @Test
    void adminCannotClaimStationTicket() {
        UUID stationId = UUID.randomUUID();
        var scope = mock(SupportTicketRepository.TicketScope.class);
        when(scope.getStationId()).thenReturn(stationId);
        when(currentProfile.requireProfile()).thenReturn(admin);
        when(tickets.findScope(ticketId)).thenReturn(Optional.of(scope));
        when(access.hasRole("OWNER")).thenReturn(false);
        when(staffAssignments.findActiveForUpdate(stationId, admin.getId(), StaffAssignmentStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> workflow.claim(ticketId, 0L))
                .isInstanceOf(AppException.class);
        verify(events, never()).saveAndFlush(any(TicketEvent.class));
    }

    @Test
    void adminCannotResolveStationTicket() {
        UUID stationId = UUID.randomUUID();
        UserProfile staff = UserProfile.builder().status(UserStatus.ACTIVE).build();
        staff.setId(UUID.randomUUID());
        SupportTicket stationTicket = SupportTicket.open("TKT-20261001-0004", TicketCategory.CHARGING_ISSUE,
                TicketPriority.HIGH, reporter, mock(Station.class), null,
                new SupportTicket.TicketDetails("Issue", "Details"));
        stationTicket.setId(ticketId);
        stationTicket.assign(staff);
        when(currentProfile.requireProfile()).thenReturn(admin);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(stationTicket));

        assertThatThrownBy(() -> workflow.changeStatus(ticketId,
                new TicketStatusRequest(0L, TicketStatus.RESOLVED, "Admin tries to resolve station ticket")))
                .isInstanceOf(AppException.class);
        verify(events, never()).saveAndFlush(any(TicketEvent.class));
    }

    @Test
    void unrelatedStaffCannotClaimStationTicket() {
        UUID stationId = UUID.randomUUID();
        UserProfile unrelatedStaff = UserProfile.builder().status(UserStatus.ACTIVE).build();
        unrelatedStaff.setId(UUID.randomUUID());
        var scope = mock(SupportTicketRepository.TicketScope.class);
        when(scope.getStationId()).thenReturn(stationId);
        when(currentProfile.requireProfile()).thenReturn(unrelatedStaff);
        when(tickets.findScope(ticketId)).thenReturn(Optional.of(scope));
        when(access.hasRole("OWNER")).thenReturn(false);
        when(staffAssignments.findActiveForUpdate(stationId, unrelatedStaff.getId(), StaffAssignmentStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> workflow.claim(ticketId, 0L))
                .isInstanceOf(AppException.class);
        verify(events, never()).saveAndFlush(any(TicketEvent.class));
    }

    @Test
    void assignHandlerOutsideStationThrowsAssignmentInvalid() {
        UUID stationId = UUID.randomUUID();
        UUID outsideHandlerId = UUID.randomUUID();
        UserProfile owner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        owner.setId(UUID.randomUUID());
        var scope = mock(SupportTicketRepository.TicketScope.class);
        when(scope.getStationId()).thenReturn(stationId);
        when(scope.getOwnerId()).thenReturn(owner.getId());
        when(currentProfile.requireProfile()).thenReturn(owner);
        when(access.hasRole("OWNER")).thenReturn(true);
        when(tickets.findScope(ticketId)).thenReturn(Optional.of(scope));
        when(staffAssignments.findActiveForUpdate(stationId, outsideHandlerId, StaffAssignmentStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> workflow.assign(ticketId,
                new AssignTicketRequest(0L, outsideHandlerId, "Assign outside handler")))
                .isInstanceOf(AppException.class);
        verify(events, never()).saveAndFlush(any(TicketEvent.class));
    }

    @Test
    void assignRevokedOrInactiveStaffThrowsAssignmentInvalid() {
        UUID stationId = UUID.randomUUID();
        UserProfile inactiveStaff = UserProfile.builder().status(UserStatus.SUSPENDED).build();
        inactiveStaff.setId(UUID.randomUUID());
        SupportTicket stationTicket = SupportTicket.open("TKT-20261001-0005", TicketCategory.CHARGING_ISSUE,
                TicketPriority.HIGH, reporter, mock(Station.class), null,
                new SupportTicket.TicketDetails("Issue", "Details"));
        stationTicket.setId(ticketId);

        var scope = mock(SupportTicketRepository.TicketScope.class);
        when(scope.getStationId()).thenReturn(stationId);
        UserProfile owner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        owner.setId(UUID.randomUUID());
        when(scope.getOwnerId()).thenReturn(owner.getId());
        when(currentProfile.requireProfile()).thenReturn(owner);
        when(access.hasRole("OWNER")).thenReturn(true);
        when(access.isOwner(stationTicket, owner.getId())).thenReturn(true);
        when(tickets.findScope(ticketId)).thenReturn(Optional.of(scope));
        when(staffAssignments.findActiveForUpdate(stationId, inactiveStaff.getId(), StaffAssignmentStatus.ACTIVE))
                .thenReturn(Optional.of(mock(StationStaffAssignment.class)));
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(stationTicket));
        when(profiles.findById(inactiveStaff.getId())).thenReturn(Optional.of(inactiveStaff));

        assertThatThrownBy(() -> workflow.assign(ticketId,
                new AssignTicketRequest(0L, inactiveStaff.getId(), "Assign suspended staff")))
                .isInstanceOf(AppException.class);
        verify(events, never()).saveAndFlush(any(TicketEvent.class));
    }

    @Test
    void driverReporterContinuedResetsAutoCloseAndReopens() {
        UserProfile adminHandler = UserProfile.builder().status(UserStatus.ACTIVE)
                .keycloakId("handler-kc").email("handler@example.test").build();
        adminHandler.setId(UUID.randomUUID());
        ticket.assign(adminHandler);
        ticket.resolve(now);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(currentProfile.requireProfile()).thenReturn(reporter);
        when(identityRoles.getRoles("handler-kc")).thenReturn(java.util.Set.of(com.thang.chargeops.common.enums.Role.ADMIN));

        workflow.changeStatus(ticketId, new TicketStatusRequest(0L, TicketStatus.IN_PROGRESS, "Problem is still there"));

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.getAutoCloseAt()).isNull();
        verify(events).saveAndFlush(any(TicketEvent.class));
    }

    @Test
    void twoStaffClaimingConcurrentlySimulated() {
        UserProfile staff1 = UserProfile.builder().status(UserStatus.ACTIVE).build();
        staff1.setId(UUID.randomUUID());
        UserProfile staff2 = UserProfile.builder().status(UserStatus.ACTIVE).build();
        staff2.setId(UUID.randomUUID());

        when(access.canHandle(eq(ticket), any())).thenReturn(true);
        when(tickets.findScope(ticketId)).thenReturn(Optional.of(mock(SupportTicketRepository.TicketScope.class)));
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        // Staff 1 claims:
        when(currentProfile.requireProfile()).thenReturn(staff1);
        workflow.claim(ticketId, 0L);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.getAssignedHandler()).isEqualTo(staff1);
        verify(events, times(1)).saveAndFlush(any(TicketEvent.class));

        // Staff 2 claims on now IN_PROGRESS ticket:
        when(currentProfile.requireProfile()).thenReturn(staff2);
        assertThatThrownBy(() -> workflow.claim(ticketId, 0L))
                .isInstanceOf(AppException.class);
        verify(events, times(1)).saveAndFlush(any(TicketEvent.class));
    }

    @Test
    void endToEndDriverOwnerStaffFlow() {
        UUID stationId = UUID.randomUUID();
        UserProfile owner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        owner.setId(UUID.randomUUID());
        UserProfile staff = UserProfile.builder().status(UserStatus.ACTIVE).build();
        staff.setId(UUID.randomUUID());
        Station station = mock(Station.class);
        when(station.getId()).thenReturn(stationId);
        when(station.getOwner()).thenReturn(owner);
        SupportTicket stationTicket = SupportTicket.open("TKT-20261001-0099", TicketCategory.CHARGING_ISSUE,
                TicketPriority.HIGH, reporter, station, null,
                new SupportTicket.TicketDetails("Issue", "Details"));
        stationTicket.setId(ticketId);

        // 1. Owner routes station ticket to active station staff
        var scope = mock(SupportTicketRepository.TicketScope.class);
        when(scope.getStationId()).thenReturn(stationId);
        when(scope.getOwnerId()).thenReturn(owner.getId());
        when(tickets.findScope(ticketId)).thenReturn(Optional.of(scope));
        when(staffAssignments.findActiveForUpdate(stationId, staff.getId(), StaffAssignmentStatus.ACTIVE))
                .thenReturn(Optional.of(mock(StationStaffAssignment.class)));
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(stationTicket));
        when(profiles.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(staffAssignments.existsByStation_IdAndStaff_IdAndStatus(stationId, staff.getId(), StaffAssignmentStatus.ACTIVE))
                .thenReturn(true);
        when(access.hasRole("OWNER")).thenReturn(true);
        when(access.isOwner(stationTicket, owner.getId())).thenReturn(true);
        when(clock.instant()).thenReturn(now);
        when(currentProfile.requireProfile()).thenReturn(owner);

        workflow.assign(ticketId, new AssignTicketRequest(0L, staff.getId(), "Routing to technician"));
        assertThat(stationTicket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(stationTicket.getAssignedHandler()).isEqualTo(staff);

        // 2. Staff resolves ticket
        when(currentProfile.requireProfile()).thenReturn(staff);
        when(access.canHandle(stationTicket, staff.getId())).thenReturn(true);
        workflow.changeStatus(ticketId, new TicketStatusRequest(0L, TicketStatus.RESOLVED, "Fixed issue"));
        assertThat(stationTicket.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(stationTicket.getAutoCloseAt()).isNotNull();

        // 3. Driver confirms resolution
        when(currentProfile.requireProfile()).thenReturn(reporter);
        workflow.changeStatus(ticketId, new TicketStatusRequest(0L, TicketStatus.CLOSED, null));
        assertThat(stationTicket.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(stationTicket.getCloseReason()).isEqualTo("REPORTER_CONFIRMED");
    }
}
