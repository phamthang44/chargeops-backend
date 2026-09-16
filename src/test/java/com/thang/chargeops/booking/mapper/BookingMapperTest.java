package com.thang.chargeops.booking.mapper;

import com.thang.chargeops.booking.dto.response.CreateBookingResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.CheckoutStatus;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.station.entity.Connector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingMapperTest {

    private final BookingMapper mapper = new BookingMapperImpl();

    @Test
    void mapsCreateResponseFromBookingAndPayment() {
        UUID bookingId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-09-16T03:00:00Z");
        Instant endAt = Instant.parse("2026-09-16T04:30:00Z");
        Instant expiresAt = Instant.parse("2026-09-16T02:10:00Z");
        Booking booking = mock(Booking.class);
        Connector connector = mock(Connector.class);
        Payment payment = mock(Payment.class);

        when(booking.getId()).thenReturn(bookingId);
        when(booking.getBookingCode()).thenReturn("BK-12345678901234567890");
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getVersion()).thenReturn(0L);
        when(booking.getConnector()).thenReturn(connector);
        when(connector.getId()).thenReturn(connectorId);
        when(booking.getStartAt()).thenReturn(startAt);
        when(booking.getEndAt()).thenReturn(endAt);
        when(booking.getTotalAmount()).thenReturn(new BigDecimal("126000.00"));
        when(booking.getExpiresAt()).thenReturn(expiresAt);
        when(payment.getId()).thenReturn(paymentId);
        when(payment.getStatus()).thenReturn(PaymentStatus.PENDING);
        when(payment.getMethod()).thenReturn(PaymentMethod.SIMULATOR);
        when(payment.getCurrency()).thenReturn("VND");

        CreateBookingResponse response = mapper.toCreateBookingResponse(booking, payment);

        assertThat(response.bookingId()).isEqualTo(bookingId);
        assertThat(response.connectorId()).isEqualTo(connectorId);
        assertThat(response.durationMin()).isEqualTo(90);
        assertThat(response.totalAmount()).isEqualTo(126_000L);
        assertThat(response.paymentHoldExpiresAt()).isEqualTo(expiresAt);
        assertThat(response.payment().paymentId()).isEqualTo(paymentId);
        assertThat(response.checkout().status()).isEqualTo(CheckoutStatus.NOT_CREATED);
    }
}
