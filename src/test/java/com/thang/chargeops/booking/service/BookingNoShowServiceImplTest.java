package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.event.BookingLifecycleEvent;
import com.thang.chargeops.booking.event.BookingLifecycleEventType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.impl.BookingNoShowServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingNoShowServiceImplTest {

    private static final UUID BOOKING_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONNECTOR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant DEADLINE = Instant.parse("2026-09-27T10:45:00Z");

    @Mock private ConnectorRepository connectorRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private BookingStatusHistoryRecorder historyRecorder;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private Connector connector;
    @Mock private Booking booking;
    @Mock private Payment payment;

    private BookingNoShowServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BookingNoShowServiceImpl(
                connectorRepository,
                bookingRepository,
                paymentRepository,
                historyRecorder,
                eventPublisher
        );
    }

    @Test
    void cancelsConfirmedBookingExactlyAtDeadlineWithoutChangingPayment() {
        stubLockedBooking(BookingStatus.CONFIRMED, DEADLINE, PaymentStatus.PAID);
        executeSystemTransition();

        assertThat(service.markNoShowIfDue(BOOKING_ID, CONNECTOR_ID, DEADLINE)).isTrue();

        verify(booking).cancel("NO_SHOW", DEADLINE);
        verify(payment, never()).markFailed();
        verify(bookingRepository).flush();
        ArgumentCaptor<BookingLifecycleEvent> event = ArgumentCaptor.forClass(BookingLifecycleEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new BookingLifecycleEvent(
                BOOKING_ID,
                BookingLifecycleEventType.NO_SHOW,
                DEADLINE
        ));
    }

    @Test
    void doesNotReleaseSixtyMinuteBookingAtStartPlusFifteenMinutes() {
        Instant startPlusFifteen = DEADLINE.minusSeconds(30 * 60);
        stubLockedBooking(BookingStatus.CONFIRMED, DEADLINE, PaymentStatus.PAID);

        assertThat(service.markNoShowIfDue(
                BOOKING_ID,
                CONNECTOR_ID,
                startPlusFifteen
        )).isFalse();

        verifyNoInteractions(historyRecorder, eventPublisher);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void checkInWinnerMakesNoShowRetryANoOp() {
        when(connectorRepository.findByIdWithLock(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        when(bookingRepository.findByIdWithLock(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(booking.getStatus()).thenReturn(BookingStatus.CHECKED_IN);

        assertThat(service.markNoShowIfDue(BOOKING_ID, CONNECTOR_ID, DEADLINE)).isFalse();

        verifyNoInteractions(paymentRepository, historyRecorder, eventPublisher);
    }

    @Test
    void refusesToRewriteFinancialStateWhenConfirmedInvariantIsBroken() {
        stubLockedBooking(BookingStatus.CONFIRMED, DEADLINE, PaymentStatus.PENDING);

        assertThat(service.markNoShowIfDue(BOOKING_ID, CONNECTOR_ID, DEADLINE)).isFalse();

        verify(payment, never()).markFailed();
        verifyNoInteractions(historyRecorder, eventPublisher);
    }

    @SuppressWarnings("unchecked")
    private void executeSystemTransition() {
        doAnswer(invocation -> {
            Consumer<Booking> transition = invocation.getArgument(3);
            transition.accept(booking);
            return null;
        }).when(historyRecorder).recordSystemTransition(
                eq(booking),
                eq(BookingStatusReason.NO_SHOW),
                eq(DEADLINE),
                any(Consumer.class)
        );
    }

    private void stubLockedBooking(
            BookingStatus status,
            Instant deadline,
            PaymentStatus paymentStatus
    ) {
        when(connectorRepository.findByIdWithLock(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        when(bookingRepository.findByIdWithLock(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(booking.getStatus()).thenReturn(status);
        when(booking.getCheckInDeadline()).thenReturn(deadline);
        lenient().when(paymentRepository.findByBookingIdWithLock(BOOKING_ID))
                .thenReturn(Optional.of(payment));
        lenient().when(payment.getStatus()).thenReturn(paymentStatus);
    }
}
