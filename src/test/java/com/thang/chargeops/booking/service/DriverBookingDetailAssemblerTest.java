package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.mapper.BookingMapper;
import com.thang.chargeops.booking.policy.DriverBookingReadPolicy;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriverBookingDetailAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    @Mock private DriverBookingReadPolicy driverBookingReadPolicy;
    @Mock private PaymentTransactionRepository paymentTransactionRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private BookingMapper bookingMapper;
    @Mock private Booking booking;
    @Mock private Payment payment;

    private DriverBookingDetailAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new DriverBookingDetailAssembler(
                driverBookingReadPolicy,
                paymentTransactionRepository,
                refundRepository,
                bookingMapper
        );
        when(driverBookingReadPolicy.snapshotForList(booking, NOW))
                .thenReturn(BookingReadSnapshot.forList(NOW, true));
        when(bookingMapper.toBookingDetailResponse(eq(booking), eq(payment), any()))
                .thenReturn(BookingDetailResponse.builder().build());
    }

    @Test
    void activePendingBookingKeepsCheckoutActionable() {
        Instant expiresAt = NOW.plusSeconds(300);
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getExpiresAt()).thenReturn(expiresAt);
        when(payment.getStatus()).thenReturn(PaymentStatus.PENDING);
        when(payment.getMethod()).thenReturn(PaymentMethod.SIMULATOR);
        when(payment.getProviderOrderRef()).thenReturn("ORDER-1");
        when(payment.getProviderExpiresAt()).thenReturn(expiresAt);
        when(payment.getQrCodeUrl()).thenReturn("https://qr.example/order-1");

        BookingReadSnapshot snapshot = assembleAndCaptureSnapshot();

        assertThat(snapshot.checkout().status()).isEqualTo(BookingDetailResponse.CheckoutState.READY);
        assertThat(snapshot.checkout().instruction()).isNotBlank();
        assertThat(snapshot.checkout().checkoutUrl()).isEqualTo("https://qr.example/order-1");
    }

    @Test
    void cancelledBookingNeverExposesActionableCheckout() {
        when(booking.getStatus()).thenReturn(BookingStatus.CANCELLED);
        when(payment.getMethod()).thenReturn(PaymentMethod.SIMULATOR);
        when(payment.getProviderExpiresAt()).thenReturn(NOW.plusSeconds(300));

        BookingReadSnapshot snapshot = assembleAndCaptureSnapshot();

        assertThat(snapshot.checkout().status()).isEqualTo(BookingDetailResponse.CheckoutState.UNAVAILABLE);
        assertThat(snapshot.checkout().instruction()).isNull();
        assertThat(snapshot.checkout().checkoutReference()).isNull();
        assertThat(snapshot.checkout().checkoutUrl()).isNull();
    }

    @Test
    void missingBookingHoldDeadlineFailsClosed() {
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(payment.getMethod()).thenReturn(PaymentMethod.BANK_TRANSFER);
        when(payment.getProviderExpiresAt()).thenReturn(NOW.plusSeconds(300));

        BookingReadSnapshot snapshot = assembleAndCaptureSnapshot();

        assertThat(snapshot.checkout().status()).isEqualTo(BookingDetailResponse.CheckoutState.UNAVAILABLE);
        assertThat(snapshot.checkout().instruction()).isNull();
        assertThat(snapshot.checkout().checkoutReference()).isNull();
        assertThat(snapshot.checkout().checkoutUrl()).isNull();
    }

    private BookingReadSnapshot assembleAndCaptureSnapshot() {
        assembler.assemble(booking, payment, NOW);
        ArgumentCaptor<BookingReadSnapshot> captor = ArgumentCaptor.forClass(BookingReadSnapshot.class);
        org.mockito.Mockito.verify(bookingMapper)
                .toBookingDetailResponse(eq(booking), eq(payment), captor.capture());
        return captor.getValue();
    }
}
