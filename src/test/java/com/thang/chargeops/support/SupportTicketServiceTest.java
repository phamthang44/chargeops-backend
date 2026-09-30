package com.thang.chargeops.support;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.support.dto.request.CreateTicketRequest;
import com.thang.chargeops.support.dto.request.MessageRequest;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketMessage;
import com.thang.chargeops.support.model.TicketActorKind;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketMessageRepository;
import com.thang.chargeops.support.service.impl.SupportTicketServiceImpl;
import com.thang.chargeops.support.service.support.SupportTicketResponseAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-30T02:00:00Z");
    private static final UUID REPORTER_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID STATION_ID = UUID.randomUUID();

    @Mock CurrentProfileProvider currentProfileProvider;
    @Mock BookingRepository bookingRepository;
    @Mock StationRepository stationRepository;
    @Mock SupportTicketRepository ticketRepository;
    @Mock TicketMessageRepository messageRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock StationStaffAssignmentRepository stationStaffAssignmentRepository;
    @Mock SupportTicketResponseAssembler ticketResponseAssembler;

    private SupportTicketServiceImpl service;
    private UserProfile reporter;

    @BeforeEach
    void setUp() {
        service = new SupportTicketServiceImpl(currentProfileProvider, bookingRepository, stationRepository,
                ticketRepository, messageRepository, jdbcTemplate, Clock.fixed(NOW, ZoneOffset.UTC),
                stationStaffAssignmentRepository, ticketResponseAssembler);
        reporter = UserProfile.builder().email("driver@example.test").displayName("Driver").build();
        reporter.setId(REPORTER_ID);
        when(currentProfileProvider.requireProfile()).thenReturn(reporter);
    }

    @Test
    void chargingIssueDerivesStationAndCreatesExactlyOneInitialMessage() {
        Station station = mock(Station.class);
        when(station.getId()).thenReturn(STATION_ID);
        Booking booking = ownedBooking(station);
        when(bookingRepository.findByIdAndDriverId(BOOKING_ID, REPORTER_ID))
                .thenReturn(Optional.of(booking));
        stubPersistence();

        var response = service.create(request(TicketCategory.CHARGING_ISSUE, BOOKING_ID, STATION_ID));

        assertThat(response.ticketCode()).isEqualTo("TKT-20260930-0001");
        assertThat(response.stationId()).isEqualTo(STATION_ID);
        assertThat(response.bookingId()).isEqualTo(BOOKING_ID);
        assertThat(response.assignedHandlerId()).isNull();
        assertThat(response.messages()).singleElement().satisfies(message ->
                assertThat(message.body()).isEqualTo("Connector stopped"));
        verify(ticketRepository).saveAndFlush(any(SupportTicket.class));
        verify(messageRepository).saveAndFlush(any(TicketMessage.class));
        verifyNoInteractions(stationRepository);
    }

    @Test
    void anotherDriversBookingAndForgedStationAreRejectedBeforeWriting() {
        when(bookingRepository.findByIdAndDriverId(BOOKING_ID, REPORTER_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(request(TicketCategory.CHARGING_ISSUE,
                BOOKING_ID, null))).isInstanceOf(AppException.class);

        Station station = mock(Station.class);
        when(station.getId()).thenReturn(STATION_ID);
        Booking forgedBooking = ownedBooking(station);
        when(bookingRepository.findByIdAndDriverId(BOOKING_ID, REPORTER_ID))
                .thenReturn(Optional.of(forgedBooking));
        assertThatThrownBy(() -> service.create(request(TicketCategory.CHARGING_ISSUE,
                BOOKING_ID, UUID.randomUUID()))).isInstanceOf(AppException.class);
        verifyNoInteractions(ticketRepository, messageRepository, jdbcTemplate);
    }

    @Test
    void stationBookingTicketKeepsStationScopeWhilePaymentRoutesWithoutIt() {
        Station station = mock(Station.class);
        when(station.getId()).thenReturn(STATION_ID);
        when(stationRepository.findById(STATION_ID)).thenReturn(Optional.of(station));
        Booking booking = ownedBooking(station);
        when(bookingRepository.findByIdAndDriverId(BOOKING_ID, REPORTER_ID))
                .thenReturn(Optional.of(booking));
        stubPersistence();

        var stationTicket = service.create(request(TicketCategory.BOOKING, null, STATION_ID));
        var paymentTicket = service.create(request(TicketCategory.PAYMENT, BOOKING_ID, STATION_ID));

        assertThat(stationTicket.stationId()).isEqualTo(STATION_ID);
        assertThat(paymentTicket.stationId()).isNull();
        assertThat(paymentTicket.bookingId()).isEqualTo(BOOKING_ID);
        assertThat(stationTicket.assignedHandlerId()).isNull();
        assertThat(paymentTicket.assignedHandlerId()).isNull();
    }

    private Booking ownedBooking(Station station) {
        Booking booking = mock(Booking.class);
        Connector connector = mock(Connector.class);
        ChargePoint chargePoint = mock(ChargePoint.class);
        when(booking.getConnector()).thenReturn(connector);
        lenient().when(booking.getId()).thenReturn(BOOKING_ID);
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(chargePoint.getStation()).thenReturn(station);
        return booking;
    }

    private void stubPersistence() {
        when(jdbcTemplate.queryForObject(eq("SELECT nextval('support_ticket_code_seq')"), eq(Long.class)))
                .thenReturn(1L, 2L);
        when(ticketRepository.saveAndFlush(any(SupportTicket.class))).thenAnswer(invocation -> {
            SupportTicket ticket = invocation.getArgument(0);
            ticket.setId(UUID.randomUUID());
            ticket.prePersistAudit();
            return ticket;
        });
        when(messageRepository.saveAndFlush(any(TicketMessage.class))).thenAnswer(invocation -> {
            TicketMessage message = invocation.getArgument(0);
            message.setId(UUID.randomUUID());
            return message;
        });
    }

    private CreateTicketRequest request(TicketCategory category, UUID bookingId, UUID stationId) {
        return new CreateTicketRequest(category, TicketPriority.HIGH,
                "Charging problem", "Connector stopped", bookingId, stationId);
    }

    @Test
    void getTicketReturnsDetailWhenActorIsReporter() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticket.getReporter()).thenReturn(reporter);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        TicketResponse expected = new TicketResponse(
                ticketId, "TKT-20260930-0001", TicketCategory.CHARGING_ISSUE, TicketPriority.HIGH,
                "Charging problem", TicketStatus.OPEN, 0L, null, null, REPORTER_ID, null, NOW,
                List.of(), List.of(), List.of()
        );
        when(ticketResponseAssembler.toResponse(ticket)).thenReturn(expected);

        TicketResponse actual = service.getTicket(ticketId);

        assertThat(actual).isEqualTo(expected);
        verify(ticketRepository).findById(ticketId);
        verify(ticketResponseAssembler).toResponse(ticket);
    }

    @Test
    void getTicketThrowsAccessDeniedWhenActorIsUnrelated() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        UserProfile unrelatedUser = UserProfile.builder().email("stranger@example.test").build();
        unrelatedUser.setId(UUID.randomUUID());
        when(ticket.getReporter()).thenReturn(unrelatedUser);
        when(ticket.getAssignedHandler()).thenReturn(null);
        when(ticket.getStation()).thenReturn(null);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.getTicket(ticketId))
                .isInstanceOf(AppException.class);
    }

    @Test
    void replyTicketAppendsMessageAndReturnsResponse() {
        UUID ticketId = UUID.randomUUID();
        UUID clientMsgId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticket.getStatus()).thenReturn(TicketStatus.OPEN);
        when(ticket.getReporter()).thenReturn(reporter);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        TicketMessage savedMessage = mock(TicketMessage.class);
        when(messageRepository.saveAndFlush(any(TicketMessage.class))).thenReturn(savedMessage);

        TicketMessageResponse expectedResponse = new TicketMessageResponse(
                UUID.randomUUID(), "Driver", TicketActorKind.REPORTER, "Updated details", NOW
        );
        when(ticketResponseAssembler.toMessageResponse(savedMessage)).thenReturn(expectedResponse);

        TicketMessageResponse actual = service.replyTicket(ticketId, clientMsgId, new MessageRequest("Updated details"));

        assertThat(actual).isEqualTo(expectedResponse);
        verify(messageRepository).saveAndFlush(any(TicketMessage.class));
    }

    @Test
    void replyTicketReplaysWhenClientMessageIdIsDuplicate() {
        UUID ticketId = UUID.randomUUID();
        UUID clientMsgId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticket.getStatus()).thenReturn(TicketStatus.OPEN);
        when(ticket.getReporter()).thenReturn(reporter);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        when(messageRepository.saveAndFlush(any(TicketMessage.class)))
                .thenThrow(new DataIntegrityViolationException("ERROR: duplicate key value violates unique constraint ux_ticket_messages_client_id"));

        TicketMessage existingMessage = mock(TicketMessage.class);
        when(messageRepository.findByAuthor_IdAndClientMessageId(REPORTER_ID, clientMsgId))
                .thenReturn(Optional.of(existingMessage));

        TicketMessageResponse expectedResponse = new TicketMessageResponse(
                UUID.randomUUID(), "Driver", TicketActorKind.REPORTER, "Original message", NOW
        );
        when(ticketResponseAssembler.toMessageResponse(existingMessage)).thenReturn(expectedResponse);

        TicketMessageResponse actual = service.replyTicket(ticketId, clientMsgId, new MessageRequest("Original message"));

        assertThat(actual).isEqualTo(expectedResponse);
        verify(messageRepository).findByAuthor_IdAndClientMessageId(REPORTER_ID, clientMsgId);
    }

    @Test
    void replyTicketThrowsClosedWhenTicketIsClosed() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticket.getStatus()).thenReturn(TicketStatus.CLOSED);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.replyTicket(ticketId, UUID.randomUUID(), new MessageRequest("Late reply")))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(messageRepository);
    }
}
