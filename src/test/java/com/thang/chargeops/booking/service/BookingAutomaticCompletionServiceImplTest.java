package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.event.BookingLifecycleEvent;
import com.thang.chargeops.booking.event.BookingLifecycleEventType;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.impl.BookingAutomaticCompletionServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingAutomaticCompletionServiceImplTest {

    private static final UUID BOOKING_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CONNECTOR_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant END_AT = Instant.parse("2026-09-27T11:00:00Z");

    @Mock private ConnectorRepository connectorRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingSessionCompletionCoordinator completionCoordinator;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private Connector connector;
    @Mock private Booking booking;

    private BookingAutomaticCompletionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BookingAutomaticCompletionServiceImpl(
                connectorRepository,
                bookingRepository,
                completionCoordinator,
                eventPublisher
        );
    }

    @Test
    void completesCheckedInBookingExactlyAtEndAt() {
        stubLockedBooking(BookingStatus.CHECKED_IN, END_AT);

        assertThat(service.completeIfDue(BOOKING_ID, CONNECTOR_ID, END_AT)).isTrue();

        verify(completionCoordinator).completeBySystem(booking, connector, END_AT);
        ArgumentCaptor<BookingLifecycleEvent> event = ArgumentCaptor.forClass(BookingLifecycleEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new BookingLifecycleEvent(
                BOOKING_ID,
                BookingLifecycleEventType.SESSION_COMPLETED,
                END_AT
        ));
    }

    @Test
    void delayedJobUsesDecisionTimeWithoutChangingOriginalRange() {
        Instant delayedDecision = END_AT.plusSeconds(90);
        stubLockedBooking(BookingStatus.CHARGING, END_AT);

        assertThat(service.completeIfDue(
                BOOKING_ID,
                CONNECTOR_ID,
                delayedDecision
        )).isTrue();

        verify(completionCoordinator).completeBySystem(booking, connector, delayedDecision);
        verify(booking, never()).startCharging(any());
    }

    @Test
    void doesNotCompleteBeforeEndAt() {
        stubLockedBooking(BookingStatus.CHARGING, END_AT);

        assertThat(service.completeIfDue(
                BOOKING_ID,
                CONNECTOR_ID,
                END_AT.minusMillis(1)
        )).isFalse();

        verifyNoInteractions(completionCoordinator, eventPublisher);
    }

    @Test
    void manualCompletionWinnerMakesWorkerRetryANoOp() {
        stubLockedBooking(BookingStatus.COMPLETED, END_AT);

        assertThat(service.completeIfDue(BOOKING_ID, CONNECTOR_ID, END_AT)).isFalse();

        verifyNoInteractions(completionCoordinator, eventPublisher);
    }

    private void stubLockedBooking(BookingStatus status, Instant endAt) {
        when(connectorRepository.findByIdWithLock(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        when(bookingRepository.findByIdWithLock(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(booking.getStatus()).thenReturn(status);
        if (status == BookingStatus.CHECKED_IN || status == BookingStatus.CHARGING) {
            when(booking.getEndAt()).thenReturn(endAt);
        }
    }
}
