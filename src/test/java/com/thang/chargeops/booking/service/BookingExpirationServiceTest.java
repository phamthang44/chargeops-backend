package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.impl.BookingExpirationServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.model.PendingPaymentSpec;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingExpirationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    @Mock
    private ConnectorRepository connectorRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private BookingStatusHistoryRecorder bookingStatusHistoryRecorder;

    private BookingExpirationServiceImpl service;

    private UUID connectorId;
    private UUID bookingId;
    private Connector connector;
    private Booking booking;

    @BeforeEach
    void setUp() {
        service = new BookingExpirationServiceImpl(
                connectorRepository,
                bookingRepository,
                paymentRepository,
                bookingStatusHistoryRecorder
        );

        connectorId = UUID.randomUUID();
        bookingId = UUID.randomUUID();

        connector = mock(Connector.class);
        booking = mock(Booking.class);

        lenient().doAnswer(invocation -> {
            Consumer<Booking> transition = invocation.getArgument(3);
            Booking target = invocation.getArgument(0);
            if (transition != null) {
                transition.accept(target);
            }
            return null;
        }).when(bookingStatusHistoryRecorder).recordSystemTransition(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Successfully expires overdue pending booking and marks pending payment failed in lock order")
    void expiresOverduePendingBookingWithPendingPayment() {
        Instant overdueExpiresAt = NOW.minus(Duration.ofSeconds(30));
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getExpiresAt()).thenReturn(overdueExpiresAt);

        Payment payment = Payment.createPending(new PendingPaymentSpec(
                booking,
                new BigDecimal("100000"),
                PaymentMethod.BANK_TRANSFER,
                "SEPAY",
                "ACC123",
                "VND"
        ));

        when(connectorRepository.findByIdWithLock(connectorId)).thenReturn(Optional.of(connector));
        when(bookingRepository.findByIdWithLock(bookingId)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdWithLock(bookingId)).thenReturn(Optional.of(payment));

        boolean result = service.expireIfDue(bookingId, connectorId, NOW);

        assertThat(result).isTrue();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        // Verify lock order: Connector -> Booking -> Payment
        InOrder inOrder = inOrder(connectorRepository, bookingRepository, paymentRepository, bookingStatusHistoryRecorder);
        inOrder.verify(connectorRepository).findByIdWithLock(connectorId);
        inOrder.verify(bookingRepository).findByIdWithLock(bookingId);
        inOrder.verify(paymentRepository).findByBookingIdWithLock(bookingId);
        inOrder.verify(bookingStatusHistoryRecorder).recordSystemTransition(
                eq(booking),
                eq(BookingStatusReason.HOLD_EXPIRED),
                eq(NOW),
                any()
        );
        verify(booking).expire();
    }

    @Test
    @DisplayName("Skips expiration when booking is not in PENDING status")
    void skipsWhenBookingNotPending() {
        when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);

        when(connectorRepository.findByIdWithLock(connectorId)).thenReturn(Optional.of(connector));
        when(bookingRepository.findByIdWithLock(bookingId)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdWithLock(bookingId)).thenReturn(Optional.empty());

        boolean result = service.expireIfDue(bookingId, connectorId, NOW);

        assertThat(result).isFalse();
        verify(bookingStatusHistoryRecorder, never()).recordSystemTransition(any(), any(), any(), any());
        verify(booking, never()).expire();
    }

    @Test
    @DisplayName("Skips expiration when booking expiresAt is after now (hold not elapsed)")
    void skipsWhenBookingNotYetDue() {
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getExpiresAt()).thenReturn(NOW.plus(Duration.ofMinutes(5)));

        when(connectorRepository.findByIdWithLock(connectorId)).thenReturn(Optional.of(connector));
        when(bookingRepository.findByIdWithLock(bookingId)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdWithLock(bookingId)).thenReturn(Optional.empty());

        boolean result = service.expireIfDue(bookingId, connectorId, NOW);

        assertThat(result).isFalse();
        verify(bookingStatusHistoryRecorder, never()).recordSystemTransition(any(), any(), any(), any());
        verify(booking, never()).expire();
    }

    @Test
    @DisplayName("Skips expiration when payment has already been PAID")
    void skipsWhenPaymentIsPaid() {
        Instant overdueExpiresAt = NOW.minus(Duration.ofSeconds(30));
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getExpiresAt()).thenReturn(overdueExpiresAt);

        Payment paidPayment = mock(Payment.class);
        when(paidPayment.getStatus()).thenReturn(PaymentStatus.PAID);

        when(connectorRepository.findByIdWithLock(connectorId)).thenReturn(Optional.of(connector));
        when(bookingRepository.findByIdWithLock(bookingId)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdWithLock(bookingId)).thenReturn(Optional.of(paidPayment));

        boolean result = service.expireIfDue(bookingId, connectorId, NOW);

        assertThat(result).isFalse();
        verify(paidPayment, never()).markFailed();
        verify(bookingStatusHistoryRecorder, never()).recordSystemTransition(any(), any(), any(), any());
        verify(booking, never()).expire();
    }

    @Test
    @DisplayName("Successfully expires overdue booking when no payment record exists")
    void expiresOverdueBookingWhenPaymentNotPresent() {
        Instant overdueExpiresAt = NOW.minus(Duration.ofSeconds(10));
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getExpiresAt()).thenReturn(overdueExpiresAt);

        when(connectorRepository.findByIdWithLock(connectorId)).thenReturn(Optional.of(connector));
        when(bookingRepository.findByIdWithLock(bookingId)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdWithLock(bookingId)).thenReturn(Optional.empty());

        boolean result = service.expireIfDue(bookingId, connectorId, NOW);

        assertThat(result).isTrue();
        verify(bookingStatusHistoryRecorder).recordSystemTransition(
                eq(booking),
                eq(BookingStatusReason.HOLD_EXPIRED),
                eq(NOW),
                any()
        );
        verify(booking).expire();
    }

    @Test
    @DisplayName("Returns false if connector not found")
    void returnsFalseWhenConnectorNotFound() {
        when(connectorRepository.findByIdWithLock(connectorId)).thenReturn(Optional.empty());

        boolean result = service.expireIfDue(bookingId, connectorId, NOW);

        assertThat(result).isFalse();
        verify(bookingRepository, never()).findByIdWithLock(any());
    }

    @Test
    @DisplayName("Returns false if booking not found")
    void returnsFalseWhenBookingNotFound() {
        when(connectorRepository.findByIdWithLock(connectorId)).thenReturn(Optional.of(connector));
        when(bookingRepository.findByIdWithLock(bookingId)).thenReturn(Optional.empty());

        boolean result = service.expireIfDue(bookingId, connectorId, NOW);

        assertThat(result).isFalse();
        verify(paymentRepository, never()).findByBookingIdWithLock(any());
    }

    @Test
    @DisplayName("Handles null parameters safely")
    void handlesNullParametersSafely() {
        assertThat(service.expireIfDue(null, connectorId, NOW)).isFalse();
        assertThat(service.expireIfDue(bookingId, null, NOW)).isFalse();
        assertThat(service.expireIfDue(bookingId, connectorId, null)).isFalse();
    }
}
