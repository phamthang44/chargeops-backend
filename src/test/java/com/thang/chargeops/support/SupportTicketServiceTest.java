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
import com.thang.chargeops.support.model.TicketListScope;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketMessageRepository;
import com.thang.chargeops.support.service.impl.SupportTicketServiceImpl;
import com.thang.chargeops.support.service.support.TicketResponseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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
    @Mock StationStaffAssignmentRepository stationStaffAssignmentRepository;
    @Mock TicketResponseService ticketResponseAssembler;

    private SupportTicketServiceImpl service;
    private UserProfile reporter;

    @BeforeEach
    void setUp() {
        service = new SupportTicketServiceImpl(currentProfileProvider, bookingRepository, stationRepository,
                ticketRepository, messageRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                stationStaffAssignmentRepository, ticketResponseAssembler, null);
        reporter = UserProfile.builder().email("driver@example.test").displayName("Driver").build();
        reporter.setId(REPORTER_ID);
        when(currentProfileProvider.requireProfile()).thenReturn(reporter);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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
        assertThat(response.description()).isEqualTo("Connector stopped");
        assertThat(response.reporterName()).isEqualTo("Driver");
        assertThat(response.bookingId()).isEqualTo(BOOKING_ID);
        assertThat(response.messageCount()).isEqualTo(1);
        assertThat(response.lastMessagePreview()).isEqualTo("Connector stopped");
        assertThat(response.updatedAt()).isNotNull();
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
        verifyNoInteractions(ticketRepository, messageRepository);
    }

    @Test
    void bookingLinkedPaymentTicketRoutesToStationOwner() {
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
        assertThat(paymentTicket.stationId()).isEqualTo(STATION_ID);
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
        when(ticketRepository.nextTicketCodeSequence()).thenReturn(1L, 2L);
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
        when(ticketResponseAssembler.toResponse(any(SupportTicket.class))).thenAnswer(invocation -> {
            SupportTicket ticket = invocation.getArgument(0);
            var first = new TicketMessageResponse(UUID.randomUUID(), reporter.getDisplayName(),
                    TicketActorKind.REPORTER, ticket.getDescription(), NOW, reporter.getId());
            var booking = ticket.getBooking();
            var station = ticket.getStation();
            return new TicketResponse(ticket.getId(), ticket.getTicketCode(), ticket.getCategory(),
                    ticket.getPriority(), ticket.getSubject(), ticket.getStatus(), ticket.getVersion(),
                    booking == null ? null : booking.getId(), station == null ? null : station.getId(),
                    reporter.getId(), null, ticket.getCreatedAt(), List.of(first), List.of(), List.of(),
                    null, null, null, 0, ticket.getDescription(),
                    station == null ? null : station.getName(), station == null ? null : station.getStationCode(),
                    station == null ? null : station.getAddressLine(), null,
                    booking == null ? null : booking.getBookingCode(),
                    booking == null ? null : booking.getStartAt(), booking == null ? null : booking.getEndAt(),
                    reporter.getDisplayName(), reporter.getPhone(), null, null,
                    ticket.getUpdatedAt(), null, null, first.body(), 1);
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
        when(ticket.getStation()).thenReturn(null);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.getTicket(ticketId))
                .isInstanceOf(AppException.class);
    }

    @Test
    void reporterScopeReturnsDetailForOwnTicket() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticket.getReporter()).thenReturn(reporter);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        service.getTicketDetail(ticketId, TicketListScope.REPORTER);

        verify(ticketResponseAssembler).toDetail(ticket);
    }

    @Test
    void reporterScopeDeniesStationOwnerWhoDidNotReport() {
        UUID ticketId = UUID.randomUUID();
        Station station = mock(Station.class);
        UserProfile owner = UserProfile.builder().email("owner@example.test").build();
        owner.setId(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("owner", "", List.of(
                        new SimpleGrantedAuthority("ROLE_OWNER"))));
        when(currentProfileProvider.requireProfile()).thenReturn(owner);
        when(station.getOwner()).thenReturn(owner);
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticket.getReporter()).thenReturn(reporter);
        when(ticket.getStation()).thenReturn(station);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        service.getTicketDetail(ticketId, null);
        verify(ticketResponseAssembler).toDetail(ticket);

        assertThatThrownBy(() -> service.getTicketDetail(ticketId, TicketListScope.REPORTER))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCodeStr())
                        .isEqualTo("TKT_ACCESS_DENIED"));
        verify(ticketResponseAssembler, times(1)).toDetail(ticket);
    }

    @Test
    void replyTicketAppendsMessageAndReturnsResponse() {
        UUID ticketId = UUID.randomUUID();
        UUID clientMsgId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticket.getStatus()).thenReturn(TicketStatus.OPEN);
        when(ticket.getReporter()).thenReturn(reporter);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        TicketMessage savedMessage = mock(TicketMessage.class);
        when(messageRepository.findByAuthor_IdAndClientMessageId(REPORTER_ID, clientMsgId))
                .thenReturn(Optional.of(savedMessage));

        TicketMessageResponse expectedResponse = new TicketMessageResponse(
                UUID.randomUUID(), "Driver", TicketActorKind.REPORTER, "Updated details", NOW, REPORTER_ID
        );
        when(ticketResponseAssembler.toMessageResponse(savedMessage)).thenReturn(expectedResponse);

        TicketMessageResponse actual = service.replyTicket(ticketId, clientMsgId, new MessageRequest("Updated details"));

        assertThat(actual).isEqualTo(expectedResponse);
        verify(messageRepository).insertIgnoreDuplicate(any(), any(), any(), any(), any(), any());
        verify(messageRepository, never()).saveAndFlush(any(TicketMessage.class));
    }

    @Test
    void adminCannotChatOnStationTicketWithoutEscalation() {
        UUID ticketId = UUID.randomUUID();
        UUID clientMsgId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertThatThrownBy(() -> service.replyAsAdmin(ticketId, clientMsgId,
                new MessageRequest("Please inspect this station issue"))).isInstanceOf(AppException.class);

        verifyNoInteractions(messageRepository);
        verify(ticketRepository, never()).saveAndFlush(any(SupportTicket.class));
    }

    @Test
    void replyTicketReplaysWhenClientMessageIdIsDuplicate() {
        UUID ticketId = UUID.randomUUID();
        UUID clientMsgId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticket.getStatus()).thenReturn(TicketStatus.OPEN);
        when(ticket.getReporter()).thenReturn(reporter);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        TicketMessage existingMessage = mock(TicketMessage.class);
        when(messageRepository.findByAuthor_IdAndClientMessageId(REPORTER_ID, clientMsgId))
                .thenReturn(Optional.of(existingMessage));

        TicketMessageResponse expectedResponse = new TicketMessageResponse(
                UUID.randomUUID(), "Driver", TicketActorKind.REPORTER, "Original message", NOW, REPORTER_ID
        );
        when(ticketResponseAssembler.toMessageResponse(existingMessage)).thenReturn(expectedResponse);

        TicketMessageResponse actual = service.replyTicket(ticketId, clientMsgId, new MessageRequest("Original message"));

        assertThat(actual).isEqualTo(expectedResponse);
        verify(messageRepository).insertIgnoreDuplicate(any(), any(), any(), any(), any(), any());
        verify(messageRepository).findByAuthor_IdAndClientMessageId(REPORTER_ID, clientMsgId);
    }

    @Test
    void replyWithoutClientMessageIdUsesRegularPersistence() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticket.getStatus()).thenReturn(TicketStatus.OPEN);
        when(ticket.getReporter()).thenReturn(reporter);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        TicketMessage savedMessage = mock(TicketMessage.class);
        when(messageRepository.saveAndFlush(any(TicketMessage.class))).thenReturn(savedMessage);

        service.replyTicket(ticketId, null, new MessageRequest("No key"));

        verify(messageRepository).saveAndFlush(any(TicketMessage.class));
        verify(messageRepository, never()).insertIgnoreDuplicate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unrelatedActorCannotLearnWhetherTicketIsClosed() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticket.getReporter()).thenReturn(null);
        when(ticket.getStation()).thenReturn(null);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.replyTicket(ticketId, UUID.randomUUID(), new MessageRequest("Probe")))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCodeStr()).isEqualTo("TKT_ACCESS_DENIED"));
        verify(ticket, never()).getStatus();
    }

    @Test
    void adminWithOwnerRoleCanFilterAnyStation() {
        UUID otherStationId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("admin", "", List.of(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_OWNER"))));
        when(stationRepository.findAllByOwner_Id(REPORTER_ID)).thenReturn(List.of());
        when(ticketRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class))).thenReturn(Page.empty());
        when(ticketResponseAssembler.toResponses(List.of())).thenReturn(List.of());

        assertThat(service.getTickets(null, otherStationId, 1, 20, TicketListScope.ACTOR)
                .getContent()).isEmpty();
        verify(ticketRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void ownerWithStaffRoleCanFilterOwnedStationWithoutStaffAssignment() {
        Station station = mock(Station.class);
        when(station.getId()).thenReturn(STATION_ID);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("owner", "", List.of(
                        new SimpleGrantedAuthority("ROLE_OWNER"),
                        new SimpleGrantedAuthority("ROLE_DRIVER"))));
        when(stationRepository.findAllByOwner_Id(REPORTER_ID)).thenReturn(List.of(station));
        when(stationStaffAssignmentRepository.findAllByStaff_IdAndStatus(
                REPORTER_ID, com.thang.chargeops.station.staff.entity.StaffAssignmentStatus.ACTIVE))
                .thenReturn(List.of());
        when(ticketRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class))).thenReturn(Page.empty());
        when(ticketResponseAssembler.toResponses(List.of())).thenReturn(List.of());

        assertThat(service.getTickets(null, STATION_ID, 1, 20, TicketListScope.ACTOR)
                .getContent()).isEmpty();
    }

    @Test
    void replyTicketThrowsClosedWhenTicketIsClosed() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticket.getStatus()).thenReturn(TicketStatus.CLOSED);
        when(ticket.getReporter()).thenReturn(reporter);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.replyTicket(ticketId, UUID.randomUUID(), new MessageRequest("Late reply")))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void replyToResolvedTicketRequiresExplicitContinueAction() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = mock(SupportTicket.class);
        when(ticket.getStatus()).thenReturn(TicketStatus.RESOLVED);
        when(ticket.getReporter()).thenReturn(reporter);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.replyTicket(ticketId, UUID.randomUUID(),
                new MessageRequest("The problem is still present")))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(messageRepository);
    }
}
