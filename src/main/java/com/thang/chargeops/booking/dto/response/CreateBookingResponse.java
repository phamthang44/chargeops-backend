package com.thang.chargeops.booking.dto.response;

import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.CheckoutStatus;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record CreateBookingResponse(
        UUID bookingId,
        String bookingCode,
        BookingStatus status,
        long version,

        UUID connectorId,
        Instant startAt,
        Instant endAt,
        int durationMin,

        long totalAmount,
        String currency,
        Instant paymentHoldExpiresAt,

        PaymentSummary payment,
        CheckoutSummary checkout
) {
    public record PaymentSummary(
            UUID paymentId,
            PaymentStatus status,
            PaymentMethod method
    ) {}

    public record CheckoutSummary(
            CheckoutStatus status
    ) {}
}
