package com.thang.chargeops.booking.policy;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.service.model.BookingReadEvaluation;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.booking.service.model.DriverBookingCapabilities;
import com.thang.chargeops.common.enums.BookingStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DriverBookingReadPolicyTest {

    private static final Instant START_AT =
            Instant.parse("2026-09-17T03:00:00Z");
    private static final Instant END_AT =
            Instant.parse("2026-09-17T04:00:00Z");

    private final DriverBookingReadPolicy policy =
            new DriverBookingReadPolicy();

    @Test
    void expiresPendingExactlyAtHoldDeadline() {
        Instant deadline = Instant.parse("2026-09-17T02:10:00Z");
        Booking booking = booking(BookingStatus.PENDING);
        when(booking.getExpiresAt()).thenReturn(deadline);

        BookingReadEvaluation evaluation = policy.evaluate(
                booking,
                BookingReadSnapshot.forList(deadline, true)
        );

        assertThat(evaluation.effectiveStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(evaluation.stateReconciliationPending()).isTrue();
        assertThat(evaluation.capabilities().canCancel()).isFalse();
    }

    @Test
    void enablesRefundAndCheckInInsideConfirmedWindows() {
        Instant evaluatedAt = Instant.parse("2026-09-17T03:05:00Z");
        Booking booking = booking(BookingStatus.CONFIRMED);
        when(booking.getFreeCancellationDeadline())
                .thenReturn(Instant.parse("2026-09-17T03:10:00Z"));
        when(booking.getCheckInDeadline())
                .thenReturn(Instant.parse("2026-09-17T03:45:00Z"));
        BookingReadSnapshot snapshot = BookingReadSnapshot.builder()
                .evaluatedAt(evaluatedAt)
                .stationAvailable(true)
                .canReportIssue(true)
                .packageRefundedAmount(26_000L)
                .build();

        BookingReadEvaluation evaluation = policy.evaluate(booking, snapshot);

        assertThat(evaluation.effectiveStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(evaluation.capabilities().canCancel()).isTrue();
        assertThat(evaluation.capabilities().refundableAmount())
                .isEqualTo(100_000L);
        assertThat(evaluation.capabilities().cancellationReason()).isEqualTo(
                DriverBookingCapabilities.CancellationReason.WITHIN_GRACE
        );
        assertThat(evaluation.capabilities().canCheckIn()).isTrue();
    }

    @Test
    void resolvesNoShowExactlyAtCheckInDeadline() {
        Instant deadline = Instant.parse("2026-09-17T03:45:00Z");
        Booking booking = booking(BookingStatus.CONFIRMED);
        when(booking.getCheckInDeadline()).thenReturn(deadline);

        BookingReadEvaluation evaluation = policy.evaluate(
                booking,
                BookingReadSnapshot.forList(deadline, true)
        );

        assertThat(evaluation.effectiveStatus())
                .isEqualTo(BookingStatus.CANCELLED);
        assertThat(evaluation.cancellationReason())
                .isEqualTo(BookingReadEvaluation.CancellationReason.NO_SHOW);
        assertThat(evaluation.capabilities().checkInReason()).isEqualTo(
                DriverBookingCapabilities.CheckInReason.WINDOW_CLOSED
        );
    }

    private Booking booking(BookingStatus status) {
        Booking booking = mock(Booking.class);
        when(booking.getStatus()).thenReturn(status);
        when(booking.getStartAt()).thenReturn(START_AT);
        when(booking.getEndAt()).thenReturn(END_AT);
        when(booking.getTotalAmount())
                .thenReturn(new BigDecimal("126000.00"));
        return booking;
    }
}
