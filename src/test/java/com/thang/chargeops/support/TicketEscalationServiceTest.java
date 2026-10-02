package com.thang.chargeops.support;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.support.dto.request.EscalateTicketRequest;
import com.thang.chargeops.support.dto.response.TicketEscalationAvailabilityResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketEscalation;
import com.thang.chargeops.support.entity.TicketMessage;
import com.thang.chargeops.support.model.TicketActorKind;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.*;
import com.thang.chargeops.support.service.TicketAccessPolicy;
import com.thang.chargeops.support.service.TicketEscalationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketEscalationServiceTest {
    @Mock CurrentProfileProvider currentProfile;
    @Mock TicketAccessPolicy access;
    @Mock SupportTicketRepository tickets;
    @Mock TicketEscalationRepository escalations;
    @Mock TicketFindingRepository findings;
    @Mock TicketEventRepository events;
    @Mock TicketMessageRepository messages;
    @Mock Clock clock;
    @InjectMocks TicketEscalationService service;

    private final UUID ticketId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-02T12:00:00Z");
    private UserProfile driver;
    private SupportTicket ticket;

    @BeforeEach
    void setUp() {
        driver = UserProfile.builder().email("driver@example.test").build();
        driver.setId(UUID.randomUUID());
        ticket = mock(SupportTicket.class);
        lenient().when(ticket.getId()).thenReturn(ticketId);
        lenient().when(ticket.getStation()).thenReturn(mock(Station.class));
        lenient().when(ticket.getStatus()).thenReturn(TicketStatus.OPEN);
        lenient().when(ticket.getReporter()).thenReturn(driver);
        lenient().when(currentProfile.requireProfile()).thenReturn(driver);
        lenient().when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        lenient().when(clock.instant()).thenReturn(now);
        lenient().when(access.hasRole("DRIVER")).thenReturn(true);
    }

    @Test
    void driverWaitsFull24HoursOfStationSilence() {
        when(findings.findFirstByTicket_IdOrderByRecordedAtDescIdDesc(ticketId)).thenReturn(Optional.empty());
        var initial = TicketMessage.create(ticket, driver, TicketActorKind.REPORTER,
                "Station cannot serve", now.minusSeconds(24 * 3600 - 1));
        when(messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId)).thenReturn(List.of(initial));
        assertThatThrownBy(() -> service.request(ticketId, new EscalateTicketRequest("No response")))
                .isInstanceOf(AppException.class);
        verify(escalations, never()).saveAndFlush(any());

        var due = TicketMessage.create(ticket, driver, TicketActorKind.REPORTER,
                "Station cannot serve", now.minusSeconds(24 * 3600));
        when(messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId)).thenReturn(List.of(due));
        when(escalations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.request(ticketId, new EscalateTicketRequest("No response"));
        assertThat(result.ticketId()).isEqualTo(ticketId);
        assertThat(result.requestedBy()).isEqualTo(driver.getId());
    }

    @Test
    void stationResponseBlocksSilenceEscalation() {
        when(findings.findFirstByTicket_IdOrderByRecordedAtDescIdDesc(ticketId)).thenReturn(Optional.empty());
        var first = TicketMessage.create(ticket, driver, TicketActorKind.REPORTER,
                "Problem", now.minusSeconds(30 * 3600));
        var owner = UserProfile.builder().email("owner@example.test").build();
        owner.setId(UUID.randomUUID());
        var answer = TicketMessage.create(ticket, owner, TicketActorKind.OWNER,
                "Investigating", now.minusSeconds(29 * 3600));
        when(messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId)).thenReturn(List.of(first, answer));
        assertThatThrownBy(() -> service.request(ticketId, new EscalateTicketRequest("I disagree")))
                .isInstanceOf(AppException.class);
    }

    @Test
    void ownerMayRequestReviewImmediately() {
        var owner = UserProfile.builder().email("owner@example.test").build();
        owner.setId(UUID.randomUUID());
        when(currentProfile.requireProfile()).thenReturn(owner);
        when(access.isOwner(ticket, owner.getId())).thenReturn(true);
        when(escalations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.request(ticketId, new EscalateTicketRequest("Unable to agree"));
        assertThat(result.requestedBy()).isEqualTo(owner.getId());
        verifyNoInteractions(findings, messages);
    }

    @Test
    void detailExplainsWhenDriverCanEscalateWithoutGuessingFrom404() {
        when(access.canRead(ticket, driver)).thenReturn(true);
        when(findings.findFirstByTicket_IdOrderByRecordedAtDescIdDesc(ticketId)).thenReturn(Optional.empty());
        var message = TicketMessage.create(ticket, driver, TicketActorKind.REPORTER,
                "Station cannot serve", now.minusSeconds(23 * 3600));
        when(messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId)).thenReturn(List.of(message));

        var state = service.describe(ticket);
        assertThat(state.escalation()).isNull();
        assertThat(state.availability().canRequest()).isFalse();
        assertThat(state.availability().availableAt()).isEqualTo(now.plusSeconds(3600));
        assertThat(state.availability().reason())
                .isEqualTo(TicketEscalationAvailabilityResponse.Reason.WAITING_FOR_STATION);
    }

    @Test
    void existingEscalationIsReturnedWithoutOfferingAnotherRequest() {
        when(access.canRead(ticket, driver)).thenReturn(true);
        when(escalations.findByTicket_Id(ticketId)).thenReturn(Optional.of(
                TicketEscalation.request(ticket, driver, now.minusSeconds(60), "Owner denied")));

        var state = service.describe(ticket);
        assertThat(state.escalation().reason()).isEqualTo("Owner denied");
        assertThat(state.availability().canRequest()).isFalse();
        assertThat(state.availability().reason())
                .isEqualTo(TicketEscalationAvailabilityResponse.Reason.ALREADY_ESCALATED);
    }

    @Test
    void lookupReturnsNullForReadableTicketWithoutEscalation() {
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(access.canRead(ticket, driver)).thenReturn(true);
        assertThat(service.get(ticketId)).isNull();
    }
}
