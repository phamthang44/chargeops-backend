package com.thang.chargeops.support;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.support.dto.request.FindingRequest;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketFinding;
import com.thang.chargeops.support.entity.TicketEvent;
import com.thang.chargeops.support.model.TicketFindingConclusion;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketFindingRepository;
import com.thang.chargeops.support.repository.TicketEventRepository;
import com.thang.chargeops.support.service.TicketAccessPolicy;
import com.thang.chargeops.support.service.TicketFindingService;
import com.thang.chargeops.support.service.support.TicketResponseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketFindingServiceTest {
    @Mock CurrentProfileProvider currentProfile;
    @Mock TicketAccessPolicy access;
    @Mock SupportTicketRepository tickets;
    @Mock TicketFindingRepository findings;
    @Mock TicketEventRepository events;
    @Mock TicketResponseService responses;
    @Mock Clock clock;
    @InjectMocks TicketFindingService service;

    @BeforeEach
    void escalatedCaseAccess() {
        lenient().when(access.canRead(any(SupportTicket.class), any(UserProfile.class))).thenReturn(true);
    }

    @Test
    void adminCanRecordFindingWithoutBeingHandlerAndVersionAdvances() {
        UUID id = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        var actor = UserProfile.builder().status(UserStatus.ACTIVE).build();
        actor.setId(UUID.randomUUID());
        var ticket = mock(SupportTicket.class);
        var station = mock(Station.class);
        var booking = mock(Booking.class);
        var connector = mock(Connector.class);
        var point = mock(ChargePoint.class);
        when(currentProfile.requireProfile()).thenReturn(actor);
        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(access.hasRole("ADMIN")).thenReturn(true);
        when(ticket.getStation()).thenReturn(station);
        when(ticket.getBooking()).thenReturn(booking);
        when(ticket.getVersion()).thenReturn(4L);
        when(station.getId()).thenReturn(stationId);
        when(booking.getConnector()).thenReturn(connector);
        when(connector.getChargePoint()).thenReturn(point);
        when(point.getStation()).thenReturn(station);
        when(clock.instant()).thenReturn(now);

        service.record(id, new FindingRequest(4L, TicketFindingConclusion.STATION_FAILURE,
                now.minusSeconds(60), "Charger failed"));

        var captor = ArgumentCaptor.forClass(TicketFinding.class);
        verify(findings).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getBooking()).isSameAs(booking);
        assertThat(captor.getValue().getRecordedBy()).isSameAs(actor);
        verify(ticket).setUpdatedAt(now);
        verify(tickets).saveAndFlush(ticket);
    }

    @Test
    void findingRequiresBookingAndRejectsStaleVersion() {
        UUID id = UUID.randomUUID();
        var actor = UserProfile.builder().status(UserStatus.ACTIVE).build();
        actor.setId(UUID.randomUUID());
        var ticket = mock(SupportTicket.class);
        when(currentProfile.requireProfile()).thenReturn(actor);
        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(access.hasRole("ADMIN")).thenReturn(true);
        when(ticket.getStation()).thenReturn(mock(Station.class));
        var request = new FindingRequest(0L, TicketFindingConclusion.NOT_STATION_FAILURE,
                Instant.parse("2026-10-02T09:00:00Z"), "Not a station fault");

        assertThatThrownBy(() -> service.record(id, request)).isInstanceOf(AppException.class);
        verifyNoInteractions(findings);
    }

    @Test
    void unrelatedStaffCannotRecordFinding() {
        UUID id = UUID.randomUUID();
        var actor = UserProfile.builder().status(UserStatus.ACTIVE).build();
        actor.setId(UUID.randomUUID());
        when(currentProfile.requireProfile()).thenReturn(actor);
        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(mock(SupportTicket.class)));

        assertThatThrownBy(() -> service.record(id, new FindingRequest(0L,
                TicketFindingConclusion.STATION_FAILURE, Instant.parse("2026-10-02T09:00:00Z"), "Fault")))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(findings);
    }

    @Test
    void disputedNotStationFailureRequiresAdminReview() {
        UUID id = UUID.randomUUID();
        var actor = UserProfile.builder().status(UserStatus.ACTIVE).build();
        actor.setId(UUID.randomUUID());
        var ticket = mock(SupportTicket.class);
        var previous = mock(TicketFinding.class);
        var dispute = mock(TicketEvent.class);
        when(currentProfile.requireProfile()).thenReturn(actor);
        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(access.isStaff(ticket, actor.getId())).thenReturn(true);
        when(ticket.getStation()).thenReturn(mock(Station.class));
        when(ticket.getBooking()).thenReturn(mock(Booking.class));
        when(ticket.getVersion()).thenReturn(2L);
        when(findings.findFirstByTicket_IdOrderByRecordedAtDescIdDesc(id)).thenReturn(Optional.of(previous));
        when(events.findFirstByTicketIdAndEventTypeOrderByCreatedAtDescIdDesc(id, "REPORTER_CONTINUED"))
                .thenReturn(Optional.of(dispute));
        when(previous.getConclusion()).thenReturn(TicketFindingConclusion.NOT_STATION_FAILURE);
        when(previous.getRecordedAt()).thenReturn(Instant.parse("2026-10-02T09:00:00Z"));
        when(dispute.getCreatedAt()).thenReturn(Instant.parse("2026-10-02T09:30:00Z"));

        assertThatThrownBy(() -> service.record(id, new FindingRequest(2L,
                TicketFindingConclusion.NOT_STATION_FAILURE,
                Instant.parse("2026-10-02T08:00:00Z"), "Still not a fault")))
                .isInstanceOf(AppException.class);
        verify(findings, never()).saveAndFlush(any(TicketFinding.class));
    }

    @Test
    void ownerOfStationCanRecordStationFailureFindingSuccessfully() {
        UUID id = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        var owner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        owner.setId(UUID.randomUUID());
        var ticket = mock(SupportTicket.class);
        var station = mock(Station.class);
        var booking = mock(Booking.class);
        var connector = mock(Connector.class);
        var point = mock(ChargePoint.class);
        when(currentProfile.requireProfile()).thenReturn(owner);
        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(access.isOwner(ticket, owner.getId())).thenReturn(true);
        when(ticket.getStation()).thenReturn(station);
        when(ticket.getBooking()).thenReturn(booking);
        when(ticket.getVersion()).thenReturn(1L);
        when(station.getId()).thenReturn(stationId);
        when(booking.getConnector()).thenReturn(connector);
        when(connector.getChargePoint()).thenReturn(point);
        when(point.getStation()).thenReturn(station);
        when(clock.instant()).thenReturn(now);

        service.record(id, new FindingRequest(1L, TicketFindingConclusion.STATION_FAILURE,
                now.minusSeconds(300), "Hardware failure verified by owner"));

        var captor = ArgumentCaptor.forClass(TicketFinding.class);
        verify(findings).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getConclusion()).isEqualTo(TicketFindingConclusion.STATION_FAILURE);
        assertThat(captor.getValue().getRecordedBy()).isSameAs(owner);
        verify(ticket).setUpdatedAt(now);
        verify(tickets).saveAndFlush(ticket);
    }

    @Test
    void activeStaffOfStationCanRecordNotStationFailureFindingSuccessfully() {
        UUID id = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        var staff = UserProfile.builder().status(UserStatus.ACTIVE).build();
        staff.setId(UUID.randomUUID());
        var ticket = mock(SupportTicket.class);
        var station = mock(Station.class);
        var booking = mock(Booking.class);
        var connector = mock(Connector.class);
        var point = mock(ChargePoint.class);
        when(currentProfile.requireProfile()).thenReturn(staff);
        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(access.isStaff(ticket, staff.getId())).thenReturn(true);
        when(ticket.getStation()).thenReturn(station);
        when(ticket.getBooking()).thenReturn(booking);
        when(ticket.getVersion()).thenReturn(2L);
        when(station.getId()).thenReturn(stationId);
        when(booking.getConnector()).thenReturn(connector);
        when(connector.getChargePoint()).thenReturn(point);
        when(point.getStation()).thenReturn(station);
        when(clock.instant()).thenReturn(now);

        service.record(id, new FindingRequest(2L, TicketFindingConclusion.NOT_STATION_FAILURE,
                now.minusSeconds(600), "User error, station is healthy"));

        var captor = ArgumentCaptor.forClass(TicketFinding.class);
        verify(findings).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getConclusion()).isEqualTo(TicketFindingConclusion.NOT_STATION_FAILURE);
        assertThat(captor.getValue().getRecordedBy()).isSameAs(staff);
        verify(tickets).saveAndFlush(ticket);
    }

    @Test
    void platformTicketCannotHaveFinding() {
        UUID id = UUID.randomUUID();
        var admin = UserProfile.builder().status(UserStatus.ACTIVE).build();
        admin.setId(UUID.randomUUID());
        var ticket = mock(SupportTicket.class);
        when(currentProfile.requireProfile()).thenReturn(admin);
        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(access.hasRole("ADMIN")).thenReturn(true);
        when(ticket.getStation()).thenReturn(null); // Platform ticket

        var request = new FindingRequest(0L, TicketFindingConclusion.NOT_STATION_FAILURE,
                Instant.parse("2026-10-02T09:00:00Z"), "Platform issue");

        assertThatThrownBy(() -> service.record(id, request))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(findings);
    }

    @Test
    void bookingOfDifferentStationCannotHaveFinding() {
        UUID id = UUID.randomUUID();
        UUID stationId1 = UUID.randomUUID();
        UUID stationId2 = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        var admin = UserProfile.builder().status(UserStatus.ACTIVE).build();
        admin.setId(UUID.randomUUID());
        var ticket = mock(SupportTicket.class);
        var station1 = mock(Station.class);
        var station2 = mock(Station.class);
        var booking = mock(Booking.class);
        var connector = mock(Connector.class);
        var point = mock(ChargePoint.class);
        when(currentProfile.requireProfile()).thenReturn(admin);
        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(access.hasRole("ADMIN")).thenReturn(true);
        when(ticket.getStation()).thenReturn(station1);
        when(ticket.getBooking()).thenReturn(booking);
        when(ticket.getVersion()).thenReturn(1L);
        when(station1.getId()).thenReturn(stationId1);
        when(station2.getId()).thenReturn(stationId2);
        when(booking.getConnector()).thenReturn(connector);
        when(connector.getChargePoint()).thenReturn(point);
        when(point.getStation()).thenReturn(station2); // Different station!

        var request = new FindingRequest(1L, TicketFindingConclusion.STATION_FAILURE,
                now.minusSeconds(60), "Cross station fault");

        assertThatThrownBy(() -> service.record(id, request))
                .isInstanceOf(AppException.class);
        verify(findings, never()).saveAndFlush(any(TicketFinding.class));
    }

    @Test
    void affectedAtInTheFutureIsRejected() {
        UUID id = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        var admin = UserProfile.builder().status(UserStatus.ACTIVE).build();
        admin.setId(UUID.randomUUID());
        var ticket = mock(SupportTicket.class);
        var station = mock(Station.class);
        var booking = mock(Booking.class);
        var connector = mock(Connector.class);
        var point = mock(ChargePoint.class);
        when(currentProfile.requireProfile()).thenReturn(admin);
        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(access.hasRole("ADMIN")).thenReturn(true);
        when(ticket.getStation()).thenReturn(station);
        when(ticket.getBooking()).thenReturn(booking);
        when(ticket.getVersion()).thenReturn(1L);
        when(station.getId()).thenReturn(stationId);
        when(booking.getConnector()).thenReturn(connector);
        when(connector.getChargePoint()).thenReturn(point);
        when(point.getStation()).thenReturn(station);
        when(clock.instant()).thenReturn(now);

        var request = new FindingRequest(1L, TicketFindingConclusion.STATION_FAILURE,
                now.plusSeconds(3600), "Future fault");

        assertThatThrownBy(() -> service.record(id, request))
                .isInstanceOf(AppException.class);
        verify(findings, never()).saveAndFlush(any(TicketFinding.class));
    }

    @Test
    void staleVersionThrowsVersionConflict() {
        UUID id = UUID.randomUUID();
        var admin = UserProfile.builder().status(UserStatus.ACTIVE).build();
        admin.setId(UUID.randomUUID());
        var ticket = mock(SupportTicket.class);
        when(currentProfile.requireProfile()).thenReturn(admin);
        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(access.hasRole("ADMIN")).thenReturn(true);
        when(ticket.getStation()).thenReturn(mock(Station.class));
        when(ticket.getBooking()).thenReturn(mock(Booking.class));
        when(ticket.getVersion()).thenReturn(3L);

        var request = new FindingRequest(2L, TicketFindingConclusion.STATION_FAILURE,
                Instant.parse("2026-10-02T09:00:00Z"), "Stale version fault");

        assertThatThrownBy(() -> service.record(id, request))
                .isInstanceOf(AppException.class);
        verify(findings, never()).saveAndFlush(any(TicketFinding.class));
    }

    @Test
    void adminCanOverrideDisputedFindingWithNewFinding() {
        UUID id = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T14:00:00Z");
        var admin = UserProfile.builder().status(UserStatus.ACTIVE).build();
        admin.setId(UUID.randomUUID());
        var ticket = mock(SupportTicket.class);
        var station = mock(Station.class);
        var booking = mock(Booking.class);
        var connector = mock(Connector.class);
        var point = mock(ChargePoint.class);
        var previous = mock(TicketFinding.class);

        when(currentProfile.requireProfile()).thenReturn(admin);
        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(access.hasRole("ADMIN")).thenReturn(true);
        when(ticket.getStation()).thenReturn(station);
        when(ticket.getBooking()).thenReturn(booking);
        when(ticket.getVersion()).thenReturn(5L);
        when(station.getId()).thenReturn(stationId);
        when(booking.getConnector()).thenReturn(connector);
        when(connector.getChargePoint()).thenReturn(point);
        when(point.getStation()).thenReturn(station);
        when(findings.findFirstByTicket_IdOrderByRecordedAtDescIdDesc(id)).thenReturn(Optional.of(previous));
        when(previous.getRecordedAt()).thenReturn(Instant.parse("2026-10-02T09:00:00Z"));
        when(clock.instant()).thenReturn(now);

        service.record(id, new FindingRequest(5L, TicketFindingConclusion.STATION_FAILURE,
                Instant.parse("2026-10-02T08:30:00Z"), "Admin review confirms station fault after dispute"));

        var captor = ArgumentCaptor.forClass(TicketFinding.class);
        verify(findings).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getConclusion()).isEqualTo(TicketFindingConclusion.STATION_FAILURE);
        assertThat(captor.getValue().getRecordedBy()).isSameAs(admin);
        verify(tickets).saveAndFlush(ticket);
    }
}
